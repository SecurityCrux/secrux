package com.securitycrux.secrux.intellij.valueflow

import com.intellij.openapi.progress.ProgressIndicator
import com.securitycrux.secrux.intellij.callgraph.CallEdge
import com.securitycrux.secrux.intellij.callgraph.CallGraph
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.callgraph.TypeHierarchyIndex
import java.util.ArrayDeque

object PointsToIndexBuilder {

    private const val CALL_RESULT_TOKEN_PREFIX = "CALLRET@"
    private const val UNKNOWN_TOKEN_PREFIX = "UNKNOWN:"
    private const val BEAN_TOKEN_PREFIX = "BEAN:"
    private const val INJECT_TOKEN_PREFIX = "INJECT:"
    private const val ALLOC_TOKEN_PREFIX = "ALLOC:"

    private data class PtKey(
        val method: MethodRef,
        val token: String,
    )

    private data class RootSet(
        val rootIds: IntArray,
        val truncated: Boolean,
    ) {
        fun isUnknownOnly(): Boolean = rootIds.size == 1 && rootIds[0] == PointsToIndex.ROOT_UNKNOWN_ID
    }

    private data class ParsedHeapFieldToken(
        val baseToken: String,
        val fieldSuffix: String,
    )

    private fun parseHeapFieldToken(token: String): ParsedHeapFieldToken? {
        if (token.startsWith("THIS:")) {
            val suffix = token.substringAfter("THIS:", missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return null
            return ParsedHeapFieldToken(baseToken = "THIS", fieldSuffix = suffix)
        }
        if (token.startsWith("STATIC:")) {
            val suffix = token.substringAfter("STATIC:", missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: return null
            return ParsedHeapFieldToken(baseToken = "STATIC", fieldSuffix = suffix)
        }
        if (!token.startsWith("HEAP(")) return null

        val start = token.indexOf('(')
        if (start < 0) return null
        var depth = 0
        var endParen = -1
        for (i in start until token.length) {
            when (token[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        endParen = i
                        break
                    }
                }
            }
        }
        if (endParen < 0) return null
        if (endParen + 1 >= token.length || token[endParen + 1] != ':') return null

        val base = token.substring(start + 1, endParen).trim().takeIf { it.isNotBlank() } ?: return null
        val suffix = token.substring(endParen + 2).trim().takeIf { it.isNotBlank() } ?: return null
        return ParsedHeapFieldToken(baseToken = base, fieldSuffix = suffix)
    }

    private fun buildHeapFieldToken(baseToken: String, fieldSuffix: String): String {
        val base = baseToken.trim()
        val suffix = fieldSuffix.trim()
        return when {
            base.isBlank() || suffix.isBlank() -> "UNKNOWN"
            base == "THIS" -> "THIS:$suffix"
            base == "STATIC" -> "STATIC:$suffix"
            else -> "HEAP($base):$suffix"
        }
    }

    private fun isUnknownToken(token: String): Boolean {
        val t = token.trim()
        return t == PointsToIndex.ROOT_UNKNOWN || t.startsWith(UNKNOWN_TOKEN_PREFIX)
    }

    private fun isRootToken(token: String): Boolean {
        val t = token.trim()
        return isUnknownToken(t) ||
            t.startsWith(BEAN_TOKEN_PREFIX) ||
            t.startsWith(INJECT_TOKEN_PREFIX) ||
            t.startsWith(ALLOC_TOKEN_PREFIX)
    }

    private fun mergeRootSets(a: RootSet, b: RootSet, maxRoots: Int): RootSet {
        val truncatedInput = a.truncated || b.truncated
        val left = a.rootIds
        val right = b.rootIds
        if (left.isEmpty()) return RootSet(rootIds = right, truncated = truncatedInput)
        if (right.isEmpty()) return RootSet(rootIds = left, truncated = truncatedInput)

        val merged = IntArray(minOf(left.size + right.size, maxRoots))
        var i = 0
        var j = 0
        var k = 0
        var overflow = false

        fun add(next: Int) {
            if (k > 0 && merged[k - 1] == next) return
            if (k < maxRoots) {
                merged[k++] = next
            } else {
                overflow = true
            }
        }

        while (i < left.size || j < right.size) {
            val next =
                when {
                    i >= left.size -> right[j++]
                    j >= right.size -> left[i++]
                    left[i] < right[j] -> left[i++]
                    right[j] < left[i] -> right[j++]
                    else -> {
                        val v = left[i]
                        i++
                        j++
                        v
                    }
                }
            add(next)
        }

        var out = merged.copyOf(k)
        if (overflow && (out.isEmpty() || out[0] != PointsToIndex.ROOT_UNKNOWN_ID)) {
            out =
                if (out.size < maxRoots) {
                    val inserted = IntArray(out.size + 1)
                    inserted[0] = PointsToIndex.ROOT_UNKNOWN_ID
                    out.copyInto(inserted, destinationOffset = 1)
                    inserted
                } else {
                    val inserted = IntArray(maxRoots)
                    inserted[0] = PointsToIndex.ROOT_UNKNOWN_ID
                    out.copyInto(inserted, destinationOffset = 1, startIndex = 0, endIndex = maxRoots - 1)
                    inserted
                }
        }

        return RootSet(rootIds = out, truncated = truncatedInput || overflow)
    }

    private data class CandidateTypes(
        val types: List<String>,
        val truncated: Boolean,
    )

    private class TypeEnv(
        typeHierarchy: TypeHierarchyIndex?
    ) {
        private val typeParentsByChild: Map<String, Set<String>> =
            typeHierarchy
                ?.edges
                ?.groupBy { it.from }
                ?.mapValues { (_, edges) -> edges.map { it.to }.toSet() }
                .orEmpty()

        private val knownTypeChildren: Set<String> = typeParentsByChild.keys

        private val allParentsCache = hashMapOf<String, Set<String>>()

        fun isAssignableMayOrUnknown(childTypeFqn: String, parentTypeFqn: String): Boolean {
            if (childTypeFqn == parentTypeFqn) return true
            if (typeParentsByChild.isEmpty()) return true
            if (childTypeFqn !in knownTypeChildren) return true
            return allParents(childTypeFqn).contains(parentTypeFqn)
        }

        private fun allParents(typeFqn: String): Set<String> {
            return allParentsCache.getOrPut(typeFqn) {
                val out = linkedSetOf<String>()
                val queue = ArrayDeque<String>()
                queue.add(typeFqn)
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val parents = typeParentsByChild[cur].orEmpty()
                    for (p in parents) {
                        if (out.add(p)) queue.addLast(p)
                    }
                    if (out.size > 2_000) break
                }
                out
            }
        }
    }

    private fun candidateBeanTypes(
        beanTypes: List<String>,
        typeEnv: TypeEnv,
        targetTypeFqn: String,
        limit: Int,
    ): CandidateTypes {
        if (beanTypes.isEmpty()) return CandidateTypes(types = emptyList(), truncated = false)
        val safeLimit = limit.coerceIn(0, 5_000)
        if (safeLimit == 0) return CandidateTypes(types = emptyList(), truncated = false)
        val merged =
            beanTypes
                .asSequence()
                .filter { bean ->
                    bean == targetTypeFqn || typeEnv.isAssignableMayOrUnknown(childTypeFqn = bean, parentTypeFqn = targetTypeFqn)
                }
                .distinct()
                .toList()
        val truncated = merged.size > safeLimit
        val types = if (truncated) merged.take(safeLimit) else merged
        return CandidateTypes(types = types, truncated = truncated)
    }

    private data class InjectionSpec(
        val targetTypeFqn: String,
    )

    private fun buildInjectableFieldSpecs(frameworkModel: FrameworkModelIndex?): Map<String, List<InjectionSpec>> {
        if (frameworkModel == null) return emptyMap()
        return buildMap<String, MutableList<InjectionSpec>> {
            for (inj in frameworkModel.injections) {
                if (inj.kind != InjectionKind.FIELD) continue
                val fieldName = inj.name?.takeIf { it.isNotBlank() } ?: continue
                val fieldKey = "THIS:${inj.ownerClassFqn}#$fieldName"
                getOrPut(fieldKey) { mutableListOf() }.add(InjectionSpec(targetTypeFqn = inj.targetTypeFqn))
            }
        }.mapValues { (_, v) -> v.toList() }
    }

    private fun buildInjectableParamSpecs(frameworkModel: FrameworkModelIndex?): Map<String, List<InjectionSpec>> {
        if (frameworkModel == null) return emptyMap()
        return buildMap<String, MutableList<InjectionSpec>> {
            for (inj in frameworkModel.injections) {
                if (inj.kind != InjectionKind.CONSTRUCTOR_PARAM && inj.kind != InjectionKind.METHOD_PARAM) continue
                val methodId = inj.methodId?.takeIf { it.isNotBlank() } ?: continue
                val keySuffix =
                    when {
                        inj.paramIndex != null -> inj.paramIndex.toString()
                        !inj.name.isNullOrBlank() -> inj.name
                        else -> null
                    } ?: continue
                val key = "$methodId:$keySuffix"
                getOrPut(key) { mutableListOf() }.add(InjectionSpec(targetTypeFqn = inj.targetTypeFqn))
            }
        }.mapValues { (_, v) -> v.toList() }
    }

    private fun possibleCallTargets(
        graph: CallGraph,
        caller: MethodRef,
        declaredCalleeId: String?,
        callOffset: Int?,
        maxTargets: Int,
    ): Set<MethodRef> {
        val out = linkedSetOf<MethodRef>()
        declaredCalleeId?.let(MethodRef::fromIdOrNull)?.let(out::add)

        if (callOffset != null) {
            for (callee in graph.outgoing[caller].orEmpty()) {
                if (out.size >= maxTargets) break
                val edge = CallEdge(caller = caller, callee = callee)
                val locs = graph.callSites[edge].orEmpty()
                if (locs.any { it.startOffset == callOffset }) {
                    out.add(callee)
                }
            }
        }

        return out
    }

    private fun mapCalleeTokenToCallerToken(
        token: String,
        call: CallSiteSummary,
        allowReturn: Boolean,
        depth: Int = 0,
    ): String? {
        if (depth > 20) return null
        val t = token.trim()
        if (t.isBlank() || t == PointsToIndex.ROOT_UNKNOWN) return null
        if (t.startsWith(UNKNOWN_TOKEN_PREFIX)) return null

        if (t.startsWith("STATIC:")) return t

        if (t == "THIS") return call.receiver?.trim()?.takeIf { it.isNotBlank() && it != PointsToIndex.ROOT_UNKNOWN }
        if (t == "RET") {
            if (!allowReturn) return null
            return call.result?.trim()?.takeIf { it.isNotBlank() && it != PointsToIndex.ROOT_UNKNOWN }
        }
        if (t.startsWith("PARAM:")) {
            val idx = t.substringAfter("PARAM:", missingDelimiterValue = "").toIntOrNull() ?: return null
            return call.args.getOrNull(idx)?.trim()?.takeIf { it.isNotBlank() && it != PointsToIndex.ROOT_UNKNOWN }
        }

        val heap = parseHeapFieldToken(t) ?: return null
        val mappedBase = mapCalleeTokenToCallerToken(heap.baseToken, call, allowReturn = allowReturn, depth = depth + 1) ?: return null
        val mapped = buildHeapFieldToken(mappedBase, heap.fieldSuffix)
        return mapped.takeIf { it.isNotBlank() && it != PointsToIndex.ROOT_UNKNOWN }
    }

    private fun allocRootToken(
        abstraction: PointsToAbstraction,
        classFqn: String,
        caller: MethodRef,
        callOffset: Int?,
    ): String {
        val cleanType = classFqn.trim()
        return when (abstraction) {
            PointsToAbstraction.TYPE -> "${ALLOC_TOKEN_PREFIX}${cleanType}"
            PointsToAbstraction.ALLOC_SITE -> {
                val site = callOffset?.toString() ?: "UNKNOWN"
                "${ALLOC_TOKEN_PREFIX}${cleanType}@${caller.id}@$site"
            }
        }
    }

    fun build(
        graph: CallGraph,
        summaries: MethodSummaryIndex,
        typeHierarchy: TypeHierarchyIndex?,
        frameworkModel: FrameworkModelIndex?,
        options: PointsToOptions,
        indicator: ProgressIndicator? = null,
    ): PointsToIndex {
        val startedAt = System.currentTimeMillis()
        val safeMaxRoots = options.maxRootsPerToken.coerceIn(1, 5_000)
        val safeMaxCallTargets = options.maxCallTargets.coerceIn(1, 500)
        val safeMaxBeanCandidates = options.maxBeanCandidates.coerceIn(0, 500)

        val rootToId = linkedMapOf<String, Int>()
        val rootDict = mutableListOf<String>()

        fun rootId(root: String): Int {
            val trimmed = root.trim().ifBlank { PointsToIndex.ROOT_UNKNOWN }
            return rootToId.getOrPut(trimmed) {
                val id = rootDict.size
                rootDict.add(trimmed)
                id
            }
        }

        // Ensure UNKNOWN is always stable id=0.
        rootId(PointsToIndex.ROOT_UNKNOWN)

        val state = hashMapOf<PtKey, RootSet>()
        val users = hashMapOf<PtKey, MutableList<PtKey>>()
        var edgeCount = 0

        fun normalizeToken(token: String?): String? {
            val t = token?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return t
        }

        fun key(method: MethodRef, token: String): PtKey {
            val t = token.trim()
            val k = PtKey(method = method, token = t)
            state.putIfAbsent(k, RootSet(rootIds = intArrayOf(), truncated = false))
            return k
        }

        val queue = ArrayDeque<PtKey>()

        fun update(k: PtKey, incoming: RootSet) {
            val current = state[k] ?: RootSet(intArrayOf(), truncated = false)
            val merged = mergeRootSets(current, incoming, maxRoots = safeMaxRoots)
            if (merged.rootIds.contentEquals(current.rootIds) && merged.truncated == current.truncated) return
            state[k] = merged
            queue.addLast(k)
        }

        fun seedRoot(method: MethodRef, token: String, rootToken: String, truncated: Boolean) {
            val root = rootToken.trim()
            val rootSet =
                when {
                    isUnknownToken(root) -> RootSet(intArrayOf(PointsToIndex.ROOT_UNKNOWN_ID), truncated = truncated)
                    else -> RootSet(intArrayOf(rootId(root)), truncated = truncated)
                }
            update(key(method, token), rootSet)
        }

        fun addDependency(from: PtKey, toMethod: MethodRef, toToken: String) {
            val toT = toToken.trim()
            if (toT.isBlank()) return
            if (isUnknownToken(toT)) {
                seedRoot(from.method, from.token, PointsToIndex.ROOT_UNKNOWN, truncated = false)
                return
            }
            if (isRootToken(toT)) {
                seedRoot(from.method, from.token, toT, truncated = false)
                return
            }
            val to = key(toMethod, toT)
            users.getOrPut(to) { mutableListOf() }.add(from)
            edgeCount++
        }

        fun addBiDependency(method: MethodRef, a: String, b: String) {
            val left = normalizeToken(a) ?: return
            val right = normalizeToken(b) ?: return
            val ka = key(method, left)
            val kb = key(method, right)
            addDependency(ka, method, right)
            addDependency(kb, method, left)
        }

        val tokensByMethod = hashMapOf<MethodRef, MutableSet<String>>()

        fun recordToken(method: MethodRef, token: String?) {
            val t = normalizeToken(token) ?: return
            tokensByMethod.getOrPut(method) { linkedSetOf() }.add(t)
        }

        fun recordTokens(method: MethodRef, tokens: Iterable<String?>) {
            for (t in tokens) recordToken(method, t)
        }

        for ((method, summary) in summaries.summaries) {
            recordTokens(method, summary.fieldsRead)
            recordTokens(method, summary.fieldsWritten)
            for (alias in summary.aliases) {
                recordToken(method, alias.left)
                recordToken(method, alias.right)
            }
            for (store in summary.stores) {
                recordToken(method, store.targetField)
                recordToken(method, store.value)
            }
            for (load in summary.loads) {
                recordToken(method, load.target)
                recordToken(method, load.sourceField)
            }
            for (call in summary.calls) {
                recordToken(method, call.receiver)
                recordTokens(method, call.args)
                recordToken(method, call.result)
                call.callOffset?.let { off -> recordToken(method, "$CALL_RESULT_TOKEN_PREFIX$off") }
            }
        }

        val typeEnv = TypeEnv(typeHierarchy)
        val beanTypes =
            frameworkModel
                ?.beans
                ?.asSequence()
                ?.map { it.typeFqn.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.toList()
                .orEmpty()

        val injectableFields = buildInjectableFieldSpecs(frameworkModel)
        val injectableParamsByKey = buildInjectableParamSpecs(frameworkModel)

        val incomingCallsByCallee = hashMapOf<MethodRef, MutableList<Pair<MethodRef, CallSiteSummary>>>()

        indicator?.text = "points-to: building constraints"
        for ((method, summary) in summaries.summaries) {
            for (copy in summary.aliases) {
                val left = normalizeToken(copy.left) ?: continue
                val right = normalizeToken(copy.right) ?: continue
                addDependency(key(method, left), toMethod = method, toToken = right)
            }
            for (load in summary.loads) {
                val target = normalizeToken(load.target) ?: continue
                val source = normalizeToken(load.sourceField) ?: continue
                addDependency(key(method, target), method, source)
            }
            for (store in summary.stores) {
                val target = normalizeToken(store.targetField) ?: continue
                val value = normalizeToken(store.value) ?: continue
                addDependency(key(method, target), method, value)
            }

            for (call in summary.calls) {
                val callOffset = call.callOffset
                val callRetToken = callOffset?.let { "$CALL_RESULT_TOKEN_PREFIX$it" }
                val resultTokens =
                    buildList {
                        callRetToken?.let(::add)
                        call.result?.let(::add)
                    }.mapNotNull(::normalizeToken)
                if (resultTokens.isEmpty() && call.receiver.isNullOrBlank() && call.args.all { it.isBlank() || it == PointsToIndex.ROOT_UNKNOWN }) {
                    continue
                }

                val targets =
                    possibleCallTargets(
                        graph = graph,
                        caller = method,
                        declaredCalleeId = call.calleeId,
                        callOffset = callOffset,
                        maxTargets = safeMaxCallTargets,
                    )

                for (callee in targets) {
                    incomingCallsByCallee.getOrPut(callee) { mutableListOf() }.add(method to call)
                }

                val receiver = normalizeToken(call.receiver)
                for (callee in targets) {
                    receiver?.let { recv ->
                        addDependency(key(callee, "THIS"), toMethod = method, toToken = recv)
                    }
                    for ((idx, arg) in call.args.withIndex()) {
                        val at = normalizeToken(arg) ?: continue
                        addDependency(key(callee, "PARAM:$idx"), toMethod = method, toToken = at)
                    }

                    val declared = call.calleeId?.let(MethodRef::fromIdOrNull)
                    val isInit = callee.name == "<init>" || declared?.name == "<init>"
                    if (isInit) {
                        val root = allocRootToken(options.abstraction, callee.classFqn, caller = method, callOffset = callOffset)
                        for (rt in resultTokens) {
                            seedRoot(method, rt, root, truncated = false)
                        }
                    } else {
                        for (rt in resultTokens) {
                            addDependency(key(method, rt), toMethod = callee, toToken = "RET")
                        }
                    }
                }

                if (targets.isEmpty()) {
                    for (rt in resultTokens) {
                        seedRoot(method, rt, PointsToIndex.ROOT_UNKNOWN, truncated = false)
                    }
                }
            }
        }

        indicator?.text = "points-to: seeding injection roots"
        for ((method, tokens) in tokensByMethod) {
            for (token in tokens) {
                val trimmed = token.trim()
                if (trimmed.startsWith("THIS:")) {
                    val specs = injectableFields[trimmed].orEmpty()
                    for (spec in specs) {
                        val candidates = candidateBeanTypes(beanTypes, typeEnv, spec.targetTypeFqn, safeMaxBeanCandidates)
                        if (candidates.types.isEmpty()) {
                            seedRoot(method, trimmed, "${INJECT_TOKEN_PREFIX}${spec.targetTypeFqn}", truncated = true)
                            continue
                        }
                        for (bean in candidates.types) {
                            seedRoot(method, trimmed, "${BEAN_TOKEN_PREFIX}$bean", truncated = false)
                        }
                        if (candidates.truncated) {
                            seedRoot(method, trimmed, "${INJECT_TOKEN_PREFIX}${spec.targetTypeFqn}", truncated = true)
                        }
                    }
                } else if (trimmed.startsWith("PARAM:")) {
                    val suffix = trimmed.substringAfter("PARAM:", missingDelimiterValue = "").trim()
                    if (suffix.isNotBlank()) {
                        val key = "${method.id}:$suffix"
                        val specs = injectableParamsByKey[key].orEmpty()
                        for (spec in specs) {
                            val candidates = candidateBeanTypes(beanTypes, typeEnv, spec.targetTypeFqn, safeMaxBeanCandidates)
                            if (candidates.types.isEmpty()) {
                                seedRoot(method, trimmed, "${INJECT_TOKEN_PREFIX}${spec.targetTypeFqn}", truncated = true)
                                continue
                            }
                            for (bean in candidates.types) {
                                seedRoot(method, trimmed, "${BEAN_TOKEN_PREFIX}$bean", truncated = false)
                            }
                            if (candidates.truncated) {
                                seedRoot(method, trimmed, "${INJECT_TOKEN_PREFIX}${spec.targetTypeFqn}", truncated = true)
                            }
                        }
                    }
                }
            }
        }

        indicator?.text = "points-to: wiring heap tokens across calls"
        for ((callee, callsites) in incomingCallsByCallee) {
            val calleeTokens = tokensByMethod[callee].orEmpty()
            if (calleeTokens.isEmpty()) continue

            val mappedTokenCache = hashMapOf<String, String?>()
            fun mappedToken(token: String, caller: MethodRef, call: CallSiteSummary): String? {
                val cacheKey = "${token}@@${caller.id}@@${call.callOffset ?: -1}"
                return mappedTokenCache.getOrPut(cacheKey) {
                    mapCalleeTokenToCallerToken(token, call, allowReturn = true)
                }
            }

            for (token in calleeTokens) {
                val t = token.trim()
                if (t.isBlank()) continue
                if (parseHeapFieldToken(t) == null) continue

                val from = key(callee, t)
                for ((caller, call) in callsites) {
                    val mapped = mappedToken(t, caller, call) ?: continue
                    val mappedNormalized = normalizeToken(mapped) ?: continue
                    addDependency(from, toMethod = caller, toToken = mappedNormalized)
                }
            }
        }

        indicator?.text = "points-to: solving"
        var processed = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            processed++
            if (processed % 10_000 == 0) {
                indicator?.checkCanceled()
                indicator?.text = "points-to: solving ($processed)"
            }
            val curSet = state[cur] ?: continue
            if (curSet.rootIds.isEmpty()) continue
            val outs = users[cur].orEmpty()
            for (dst in outs) {
                update(dst, curSet)
            }
        }

        val byMethod = linkedMapOf<MethodRef, MutableMap<String, PointsToEntry>>()
        var truncatedEntries = 0
        for ((k, v) in state) {
            if (v.rootIds.isEmpty()) continue
            byMethod.getOrPut(k.method) { linkedMapOf() }[k.token] =
                PointsToEntry(rootIds = v.rootIds, truncated = v.truncated)
            if (v.truncated) truncatedEntries++
        }

        val stats =
            PointsToStats(
                entries = byMethod.values.sumOf { it.size },
                methods = byMethod.size,
                rootDictSize = rootDict.size,
                edges = edgeCount,
                buildMillis = System.currentTimeMillis() - startedAt,
                truncatedEntries = truncatedEntries,
            )

        return PointsToIndex(
            byMethod = byMethod.mapValues { (_, v) -> v.toMap() },
            rootDict = rootDict.toList(),
            options = options,
            stats = stats,
        )
    }
}
