package com.securitycrux.secrux.intellij.valueflow

import com.securitycrux.secrux.intellij.callgraph.CallEdge
import com.securitycrux.secrux.intellij.callgraph.CallGraph
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.callgraph.TypeHierarchyIndex
import java.util.ArrayDeque

enum class ValueFlowEdgeKind {
    ALIAS,
    LOAD,
    STORE,
    HEAP_STORE,
    CALL_ARG,
    CALL_RECEIVER,
    CALL_RETURN,
    CALL_RESULT,
    INJECT,
}

data class ValueFlowNode(
    val method: MethodRef,
    val token: String,
)

data class ValueFlowEdge(
    val kind: ValueFlowEdgeKind,
    val from: ValueFlowNode,
    val to: ValueFlowNode,
    val offset: Int?,
    val filePath: String? = null,
    val details: String? = null,
)

data class ValueFlowTrace(
    val start: ValueFlowNode,
    val end: ValueFlowNode,
    val edges: List<ValueFlowEdge>,
)

class ValueFlowTracer(
    private val graph: CallGraph,
    private val summaries: MethodSummaryIndex,
    private val typeHierarchy: TypeHierarchyIndex? = null,
    private val frameworkModel: FrameworkModelIndex? = null,
) {
    private data class TraceState(
        val node: ValueFlowNode,
        val depth: Int,
    )

    private data class Parent(
        val prev: ValueFlowNode,
        val via: ValueFlowEdge,
    )

    private data class StoreSite(
        val method: MethodRef,
        val targetField: String,
        val baseToken: String,
        val fieldSuffix: String,
        val value: String,
        val offset: Int?,
    )

    private data class InjectionSpec(
        val targetTypeFqn: String,
        val filePath: String?,
        val startOffset: Int?,
    )

    private val storeSitesByFieldSuffix: Map<String, List<StoreSite>> =
        buildMap<String, MutableList<StoreSite>> {
            for ((method, summary) in summaries.summaries) {
                for (store in summary.stores) {
                    val targetField = store.targetField.trim().takeIf { it.isNotBlank() } ?: continue
                    val parsed = parseHeapFieldToken(targetField) ?: continue
                    getOrPut(parsed.fieldSuffix) { mutableListOf() }.add(
                        StoreSite(
                            method = method,
                            targetField = targetField,
                            baseToken = parsed.baseToken,
                            fieldSuffix = parsed.fieldSuffix,
                            value = store.value,
                            offset = store.offset,
                        )
                    )
                }
            }
        }.mapValues { (_, v) -> v.toList() }

    private val typeParentsByChild: Map<String, Set<String>> =
        typeHierarchy
            ?.edges
            ?.groupBy { it.from }
            ?.mapValues { (_, edges) -> edges.map { it.to }.toSet() }
            .orEmpty()

    private val knownTypeChildren: Set<String> = typeParentsByChild.keys

    private val allParentsCache = hashMapOf<String, Set<String>>()

    private data class PointsToResult(
        val roots: Set<String>,
        val truncated: Boolean,
    )

    private data class PointsToKey(
        val method: MethodRef,
        val token: String,
    )

    private val pointsToCacheMaxSize = 20_000

    private val pointsToCache: LinkedHashMap<PointsToKey, PointsToResult> =
        object : LinkedHashMap<PointsToKey, PointsToResult>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PointsToKey, PointsToResult>?): Boolean {
                return size > pointsToCacheMaxSize
            }
        }

    private val injectableFields: Map<String, List<InjectionSpec>> =
        buildMap<String, MutableList<InjectionSpec>> {
            val model = frameworkModel ?: return@buildMap
            for (inj in model.injections) {
                if (inj.kind != InjectionKind.FIELD) continue
                val fieldName = inj.name?.takeIf { it.isNotBlank() } ?: continue
                val fieldKey = "THIS:${inj.ownerClassFqn}#$fieldName"
                getOrPut(fieldKey) { mutableListOf() }.add(
                    InjectionSpec(
                        targetTypeFqn = inj.targetTypeFqn,
                        filePath = inj.filePath,
                        startOffset = inj.startOffset,
                    )
                )
            }
        }.mapValues { (_, v) -> v.toList() }

    private val injectableParamsByKey: Map<String, List<InjectionSpec>> =
        buildMap<String, MutableList<InjectionSpec>> {
            val model = frameworkModel ?: return@buildMap
            for (inj in model.injections) {
                if (inj.kind != InjectionKind.CONSTRUCTOR_PARAM && inj.kind != InjectionKind.METHOD_PARAM) continue
                val methodId = inj.methodId?.takeIf { it.isNotBlank() } ?: continue
                val keySuffix =
                    when {
                        inj.paramIndex != null -> inj.paramIndex.toString()
                        !inj.name.isNullOrBlank() -> inj.name
                        else -> null
                    } ?: continue
                val key = "$methodId:$keySuffix"
                getOrPut(key) { mutableListOf() }.add(
                    InjectionSpec(
                        targetTypeFqn = inj.targetTypeFqn,
                        filePath = inj.filePath,
                        startOffset = inj.startOffset,
                    )
                )
            }
        }.mapValues { (_, v) -> v.toList() }

    private val beanTypes: List<String> =
        frameworkModel
            ?.beans
            ?.asSequence()
            ?.map { it.typeFqn.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            .orEmpty()

    fun traceToRoot(
        startMethod: MethodRef,
        startToken: String,
        maxDepth: Int = 10,
        maxStates: Int = 2_000,
        maxHeapWritersPerStep: Int = 25,
    ): ValueFlowTrace? =
        traceToRoots(
            startMethod = startMethod,
            startToken = startToken,
            maxDepth = maxDepth,
            maxStates = maxStates,
            maxHeapWritersPerStep = maxHeapWritersPerStep,
            maxTraces = 1,
        ).firstOrNull()

    fun traceToRoots(
        startMethod: MethodRef,
        startToken: String,
        maxDepth: Int = 10,
        maxStates: Int = 2_000,
        maxHeapWritersPerStep: Int = 25,
        maxTraces: Int = 3,
    ): List<ValueFlowTrace> {
        if (startToken.isBlank()) return emptyList()
        if (maxDepth <= 0 || maxStates <= 0) return emptyList()
        if (maxTraces <= 0) return emptyList()
        val safeMaxTraces = maxTraces.coerceIn(1, 50)

        val start = ValueFlowNode(method = startMethod, token = startToken)
        val queue = ArrayDeque<TraceState>()
        val visited = linkedSetOf<ValueFlowNode>()
        val parents = hashMapOf<ValueFlowNode, Parent>()

        fun enqueue(next: ValueFlowNode, depth: Int, via: ValueFlowEdge) {
            if (next.token.isBlank()) return
            if (visited.size >= maxStates) return
            if (!visited.add(next)) return
            parents[next] = Parent(prev = via.from, via = via)
            queue.addLast(TraceState(node = next, depth = depth))
        }

        fun isRoot(node: ValueFlowNode, depth: Int): Boolean {
            if (depth >= maxDepth) return true
            if (node.token == "UNKNOWN" || node.token.startsWith(UNKNOWN_TOKEN_PREFIX)) return true
            if (node.token.startsWith(BEAN_TOKEN_PREFIX) || node.token.startsWith(INJECT_TOKEN_PREFIX)) return true
            if (node.token.startsWith(ALLOC_TOKEN_PREFIX)) return true
            if (node.method in graph.entryPoints) return true
            if (graph.incoming[node.method].isNullOrEmpty()) return true
            return false
        }

        fun rootPriority(node: ValueFlowNode, depth: Int): Int {
            if (node.token.startsWith(BEAN_TOKEN_PREFIX) || node.token.startsWith(INJECT_TOKEN_PREFIX)) return 0
            if (node.method in graph.entryPoints) return 1
            if (node.token.startsWith(ALLOC_TOKEN_PREFIX)) return 2
            if (node.token == "UNKNOWN" || node.token.startsWith(UNKNOWN_TOKEN_PREFIX)) return 50
            if (graph.incoming[node.method].isNullOrEmpty()) return 3
            if (depth >= maxDepth) return 80
            return 90
        }

        if (isRoot(start, 0)) {
            return listOf(ValueFlowTrace(start = start, end = start, edges = emptyList()))
        }

        visited.add(start)
        queue.addLast(TraceState(node = start, depth = 0))

        val candidates = linkedMapOf<ValueFlowNode, Pair<Int, Int>>()

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()

            if (current != start && isRoot(current, depth)) {
                val key = rootPriority(current, depth) to depth
                val existing = candidates[current]
                val better =
                    existing == null ||
                        key.first < existing.first ||
                        (key.first == existing.first && key.second < existing.second)
                if (better) {
                    candidates[current] = key
                }
            }

            if (depth >= maxDepth) continue
            val summary = summaries.summaries[current.method]

            if (summary != null) {
                expandAliases(current, summary).forEach { edge -> enqueue(edge.to, depth + 1, edge) }
                expandLoads(current, summary).forEach { edge -> enqueue(edge.to, depth + 1, edge) }
                expandStores(current, summary).forEach { edge -> enqueue(edge.to, depth + 1, edge) }
                expandCallResultsToCallee(current, summary).forEach { edge -> enqueue(edge.to, depth + 1, edge) }
            }
            expandInjectEdges(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge)
            }
            expandCallToCaller(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge)
            }
            expandHeapStores(current, maxHeapWritersPerStep).forEach { edge ->
                enqueue(edge.to, depth + 1, edge)
            }
        }

        if (candidates.isEmpty()) return emptyList()

        return candidates.entries
            .sortedWith(compareBy({ it.value.first }, { it.value.second }))
            .take(safeMaxTraces)
            .map { (end, _) ->
                val edges = reconstructEdges(start = start, end = end, parents = parents)
                ValueFlowTrace(start = start, end = end, edges = edges)
            }
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

    private data class CandidateTypes(
        val types: List<String>,
        val truncated: Boolean,
    )

    private fun expandInjectEdges(node: ValueFlowNode, maxCandidatesPerInjection: Int = 25): List<ValueFlowEdge> {
        if (frameworkModel == null) return emptyList()
        val token = node.token
        val out = mutableListOf<ValueFlowEdge>()

        fun emit(spec: InjectionSpec) {
            val candidates = candidateBeanTypes(spec.targetTypeFqn, maxCandidatesPerInjection)
            if (candidates.types.isEmpty()) {
                out.add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.INJECT,
                        from = node,
                        to = ValueFlowNode(method = node.method, token = "${INJECT_TOKEN_PREFIX}${spec.targetTypeFqn}"),
                        offset = spec.startOffset,
                        filePath = spec.filePath,
                        details = "inject ${spec.targetTypeFqn}",
                    )
                )
                return
            }

            for (bean in candidates.types) {
                out.add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.INJECT,
                        from = node,
                        to = ValueFlowNode(method = node.method, token = "${BEAN_TOKEN_PREFIX}$bean"),
                        offset = spec.startOffset,
                        filePath = spec.filePath,
                        details = "inject ${spec.targetTypeFqn} <= $bean",
                    )
                )
            }

            if (candidates.truncated) {
                out.add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.INJECT,
                        from = node,
                        to = ValueFlowNode(method = node.method, token = "${INJECT_TOKEN_PREFIX}${spec.targetTypeFqn}"),
                        offset = spec.startOffset,
                        filePath = spec.filePath,
                        details = "inject ${spec.targetTypeFqn} <= ${UNKNOWN_TOKEN_PREFIX}TRUNCATED",
                    )
                )
            }
        }

        if (token.startsWith("THIS:")) {
            injectableFields[token].orEmpty().forEach(::emit)
        }
        if (token.startsWith("PARAM:")) {
            val suffix = token.substringAfter("PARAM:", missingDelimiterValue = "")
            if (suffix.isNotBlank()) {
                val key = "${node.method.id}:$suffix"
                injectableParamsByKey[key].orEmpty().forEach(::emit)
            }
        }

        return out
    }

    private fun candidateBeanTypes(targetTypeFqn: String, limit: Int): CandidateTypes {
        if (beanTypes.isEmpty()) return CandidateTypes(types = emptyList(), truncated = false)
        val safeLimit = limit.coerceIn(0, 200)
        if (safeLimit == 0) return CandidateTypes(types = emptyList(), truncated = false)

        val exact = mutableListOf<String>()
        val assignable = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        for (bean in beanTypes) {
            when {
                bean == targetTypeFqn -> exact.add(bean)
                isAssignableMay(bean, targetTypeFqn) -> assignable.add(bean)
                else -> {
                    if (bean !in knownTypeChildren) unknown.add(bean)
                }
            }
        }

        val merged = (exact + assignable + unknown).distinct()
        val truncated = merged.size > safeLimit
        val types = if (truncated) merged.take(safeLimit) else merged
        return CandidateTypes(types = types, truncated = truncated)
    }

    private fun isAssignableMay(childTypeFqn: String, parentTypeFqn: String): Boolean {
        if (childTypeFqn == parentTypeFqn) return true
        if (typeParentsByChild.isEmpty()) return false
        if (childTypeFqn !in knownTypeChildren) return true
        return allParents(childTypeFqn).contains(parentTypeFqn)
    }

    private fun isAssignableMayOrUnknown(childTypeFqn: String, parentTypeFqn: String): Boolean {
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

    private fun expandAliases(node: ValueFlowNode, summary: MethodSummary): List<ValueFlowEdge> {
        val out = mutableListOf<ValueFlowEdge>()
        val token = node.token

        val heap = parseHeapFieldToken(token)
        if (heap != null) {
            for (alias in summary.aliases) {
                val otherBase =
                    when {
                        alias.left == heap.baseToken -> alias.right
                        alias.right == heap.baseToken -> alias.left
                        else -> null
                    } ?: continue
                if (otherBase == "UNKNOWN") continue
                out.add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.ALIAS,
                        from = node,
                        to = ValueFlowNode(method = node.method, token = buildHeapFieldToken(otherBase, heap.fieldSuffix)),
                        offset = alias.offset,
                        details = "heapBase ${heap.baseToken} <-> $otherBase",
                    )
                )
            }
        }

        for (alias in summary.aliases) {
            val next =
                when {
                    alias.left == token -> alias.right
                    alias.right == token -> alias.left
                    else -> null
                } ?: continue
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.ALIAS,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = next),
                    offset = alias.offset,
                )
            )
        }
        return out
    }

    private fun expandLoads(node: ValueFlowNode, summary: MethodSummary): List<ValueFlowEdge> {
        val out = mutableListOf<ValueFlowEdge>()
        val token = node.token
        for (load in summary.loads) {
            if (load.target != token) continue
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.LOAD,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = load.sourceField),
                    offset = load.offset,
                )
            )
        }
        return out
    }

    private fun expandCallResultsToCallee(node: ValueFlowNode, summary: MethodSummary): List<ValueFlowEdge> {
        val token = node.token
        val out = mutableListOf<ValueFlowEdge>()

        fun addTargets(call: CallSiteSummary) {
            val callOffset = call.callOffset
            val declared = call.calleeId?.let(MethodRef::fromIdOrNull)
            if (declared != null && declared.name == "<init>") {
                out.add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.CALL_RESULT,
                        from = node,
                        to = ValueFlowNode(
                            method = node.method,
                            token = "${ALLOC_TOKEN_PREFIX}${declared.classFqn}${callOffset?.let { "@$it" }.orEmpty()}",
                        ),
                        offset = callOffset,
                        details = "alloc ${declared.classFqn}",
                    )
                )
                return
            }
            val targets = possibleCallTargets(caller = node.method, declaredCalleeId = call.calleeId, callOffset = callOffset)
            for (target in targets) {
                out.add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.CALL_RESULT,
                        from = node,
                        to = ValueFlowNode(method = target, token = "RET"),
                        offset = callOffset,
                        details = "${node.method.id} <- ${target.id} return",
                    )
                )
            }
        }

        if (token.startsWith(CALL_RESULT_TOKEN_PREFIX)) {
            val callOffset = token.substringAfter(CALL_RESULT_TOKEN_PREFIX).toIntOrNull()
            if (callOffset != null) {
                for (call in summary.calls) {
                    if (call.callOffset == callOffset) addTargets(call)
                }
            }
        }

        for (call in summary.calls) {
            if (call.result != token) continue
            addTargets(call)
        }

        return out
    }

    private fun possibleCallTargets(
        caller: MethodRef,
        declaredCalleeId: String?,
        callOffset: Int?,
        maxTargets: Int = 50,
    ): List<MethodRef> {
        val out = linkedSetOf<MethodRef>()
        declaredCalleeId?.let(MethodRef::fromIdOrNull)?.let(out::add)

        if (callOffset != null) {
            for (callee in graph.outgoing[caller].orEmpty()) {
                if (out.size >= maxTargets) break
                val edge = CallEdge(caller = caller, callee = callee)
                val loc = graph.callSites[edge] ?: continue
                if (loc.startOffset == callOffset) {
                    out.add(callee)
                }
            }
        }

        return out.toList()
    }

    private fun expandStores(node: ValueFlowNode, summary: MethodSummary): List<ValueFlowEdge> {
        val out = mutableListOf<ValueFlowEdge>()
        val token = node.token
        for (store in summary.stores) {
            if (store.targetField != token) continue
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.STORE,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = store.value),
                    offset = store.offset,
                )
            )
        }
        return out
    }

    private fun expandHeapStores(node: ValueFlowNode, maxWriters: Int): List<ValueFlowEdge> {
        val token = node.token
        val heap = parseHeapFieldToken(token) ?: return emptyList()

        val writers = storeSitesByFieldSuffix[heap.fieldSuffix].orEmpty()
        if (writers.isEmpty()) return emptyList()

        val safeMaxWriters = maxWriters.coerceIn(1, 200)
        val incomingCallers = graph.incoming[node.method].orEmpty()

        fun ownerTypeFromFieldSuffix(fieldSuffix: String): String? {
            val owner = fieldSuffix.substringBefore('#', missingDelimiterValue = "").trim()
            return owner.takeIf { it.isNotBlank() }
        }

        val expectedOwnerType = ownerTypeFromFieldSuffix(heap.fieldSuffix)

        val baseNode = ValueFlowNode(method = node.method, token = heap.baseToken)
        val basePointsTo = pointsToRoots(baseNode)
        val baseFilteredRoots =
            filterPointsToRootsByExpectedType(basePointsTo.roots, expectedOwnerType)
        val baseIsUnknown =
            basePointsTo.truncated || baseFilteredRoots.isEmpty() || baseFilteredRoots.any(::isUnknownToken)

        data class ScoredStore(
            val site: StoreSite,
            val score: Int,
            val reason: String?,
        )

        val orderedCandidates =
            writers
                .asSequence()
                .filter { it.method != node.method }
                .sortedWith(
                    compareByDescending<StoreSite> { it.method in incomingCallers }
                        .thenByDescending { it.method.classFqn == node.method.classFqn }
                )
                .toList()

        val maxCandidatesToCheck = (safeMaxWriters * 25).coerceIn(safeMaxWriters, 2_000)
        val candidatesToCheck = orderedCandidates.take(maxCandidatesToCheck)
        val candidateOverflow = orderedCandidates.size > candidatesToCheck.size

        val scored = mutableListOf<ScoredStore>()
        for (site in candidatesToCheck) {
            val storeBaseNode = ValueFlowNode(method = site.method, token = site.baseToken)
            val storePointsTo = pointsToRoots(storeBaseNode)
            val storeFilteredRoots =
                filterPointsToRootsByExpectedType(storePointsTo.roots, expectedOwnerType)
            val storeIsUnknown =
                storePointsTo.truncated || storeFilteredRoots.isEmpty() || storeFilteredRoots.any(::isUnknownToken)

            if (baseIsUnknown || storeIsUnknown) {
                val bonus =
                    (if (site.method in incomingCallers) 100 else 0) +
                        (if (site.method.classFqn == node.method.classFqn) 10 else 0)
                scored.add(
                    ScoredStore(
                        site = site,
                        score = bonus,
                        reason = "points-to unknown",
                    )
                )
                continue
            }

            val common = baseFilteredRoots.intersect(storeFilteredRoots)
            if (common.isEmpty()) continue

            val bonus =
                (if (site.method in incomingCallers) 100 else 0) +
                    (if (site.method.classFqn == node.method.classFqn) 10 else 0)
            scored.add(
                ScoredStore(
                    site = site,
                    score = bonus + common.size,
                    reason = "points-to common=${common.take(3)}",
                )
            )
        }

        val prioritized =
            scored
                .sortedByDescending { it.score }
                .take(safeMaxWriters)
                .toList()

        val droppedByBudget = (scored.size - prioritized.size).coerceAtLeast(0)
        val omittedByCandidateCap = if (candidateOverflow) orderedCandidates.size - candidatesToCheck.size else 0
        val omittedTotal = droppedByBudget + omittedByCandidateCap
        val truncated = omittedTotal > 0

        return buildList {
            addAll(
                prioritized.map { item ->
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.HEAP_STORE,
                        from = node,
                        to = ValueFlowNode(method = item.site.method, token = item.site.value),
                        offset = item.site.offset,
                        details = token + item.reason?.let { " ($it)" }.orEmpty(),
                    )
                }
            )
            if (truncated) {
                add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.HEAP_STORE,
                        from = node,
                        to =
                            ValueFlowNode(
                                method = node.method,
                                token = "${UNKNOWN_TOKEN_PREFIX}HEAP_STORE_TRUNCATED:+$omittedTotal",
                            ),
                        offset = null,
                        details = token,
                    )
                )
            }
        }
    }

    private fun pointsToRoots(
        node: ValueFlowNode,
        maxDepth: Int = 10,
        maxStates: Int = 1_500,
        maxRoots: Int = 60,
    ): PointsToResult {
        val token = node.token.trim()
        if (token.isBlank()) return PointsToResult(roots = emptySet(), truncated = false)
        val key = PointsToKey(method = node.method, token = token)
        pointsToCache[key]?.let { return it }

        val safeMaxDepth = maxDepth.coerceIn(0, 30)
        val safeMaxStates = maxStates.coerceIn(1, 50_000)
        val safeMaxRoots = maxRoots.coerceIn(1, 5_000)

        val roots = linkedSetOf<String>()
        val visited = linkedSetOf<ValueFlowNode>()
        val queue = ArrayDeque<Pair<ValueFlowNode, Int>>()
        var truncated = false

        fun enqueue(next: ValueFlowNode, depth: Int) {
            val t = next.token.trim()
            if (t.isBlank()) return
            val normalized = if (t == next.token) next else next.copy(token = t)
            if (visited.size >= safeMaxStates) {
                truncated = true
                return
            }
            if (!visited.add(normalized)) return
            queue.addLast(normalized to depth)
        }

        enqueue(ValueFlowNode(method = node.method, token = token), depth = 0)

        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            val currentToken = current.token

            val rootToken =
                when {
                    currentToken.startsWith(ALLOC_TOKEN_PREFIX) -> currentToken
                    currentToken.startsWith(BEAN_TOKEN_PREFIX) -> currentToken
                    currentToken.startsWith(INJECT_TOKEN_PREFIX) -> currentToken
                    isUnknownToken(currentToken) -> "UNKNOWN"
                    else -> null
                }

            if (rootToken != null) {
                roots.add(rootToken)
                if (roots.size >= safeMaxRoots) {
                    truncated = true
                    break
                }
                continue
            }

            if (depth >= safeMaxDepth) continue

            val summary = summaries.summaries[current.method]
            if (summary != null) {
                expandAliases(current, summary).forEach { edge -> enqueue(edge.to, depth + 1) }
                expandLoads(current, summary).forEach { edge -> enqueue(edge.to, depth + 1) }
                expandStores(current, summary).forEach { edge -> enqueue(edge.to, depth + 1) }
                expandCallResultsToCallee(current, summary).forEach { edge -> enqueue(edge.to, depth + 1) }
            }

            expandInjectEdges(current).forEach { edge -> enqueue(edge.to, depth + 1) }
            expandCallToCaller(current).forEach { edge -> enqueue(edge.to, depth + 1) }
        }

        val result = PointsToResult(roots = roots.toSet(), truncated = truncated)
        pointsToCache[key] = result
        return result
    }

    private fun isUnknownToken(token: String): Boolean {
        val t = token.trim()
        return t == "UNKNOWN" || t.startsWith(UNKNOWN_TOKEN_PREFIX)
    }

    private fun rootTypeFqn(rootToken: String): String? {
        val t = rootToken.trim()
        return when {
            t.startsWith(ALLOC_TOKEN_PREFIX) ->
                t.removePrefix(ALLOC_TOKEN_PREFIX).substringBefore('@').trim().takeIf { it.isNotBlank() }
            t.startsWith(BEAN_TOKEN_PREFIX) ->
                t.removePrefix(BEAN_TOKEN_PREFIX).trim().takeIf { it.isNotBlank() }
            t.startsWith(INJECT_TOKEN_PREFIX) ->
                t.removePrefix(INJECT_TOKEN_PREFIX).trim().takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun filterPointsToRootsByExpectedType(roots: Set<String>, expectedTypeFqn: String?): Set<String> {
        val expected = expectedTypeFqn?.trim().takeIf { !it.isNullOrBlank() } ?: return roots
        if (roots.isEmpty()) return roots
        return roots.filterTo(linkedSetOf()) { root ->
            if (isUnknownToken(root)) return@filterTo true
            val type = rootTypeFqn(root) ?: return@filterTo true
            isAssignableMayOrUnknown(type, expected)
        }
    }

    private fun expandCallToCaller(node: ValueFlowNode): List<ValueFlowEdge> {
        val out = mutableListOf<ValueFlowEdge>()
        val token = node.token

        val incomingCallers = graph.incoming[node.method].orEmpty()
        if (incomingCallers.isEmpty()) return emptyList()

        fun heapRootBaseToken(token: String, maxDepth: Int = 20): String? {
            var cur = token
            var depth = 0
            while (depth++ < maxDepth) {
                val heap = parseHeapFieldToken(cur) ?: break
                cur = heap.baseToken
            }
            return cur.takeIf { it.isNotBlank() }
        }

        fun callKindForRootBase(rootBase: String?): ValueFlowEdgeKind? =
            when {
                rootBase == null -> null
                rootBase == "THIS" -> ValueFlowEdgeKind.CALL_RECEIVER
                rootBase == "RET" -> ValueFlowEdgeKind.CALL_RETURN
                rootBase.startsWith("PARAM:") -> ValueFlowEdgeKind.CALL_ARG
                else -> null
            }

        fun mapCalleeTokenToCallerToken(token: String, call: CallSiteSummary, depth: Int = 0): String? {
            if (depth > 20) return null
            val t = token.trim()
            if (t.isBlank() || t == "UNKNOWN") return null

            if (t.startsWith("STATIC:")) return t

            if (t == "THIS") return call.receiver?.trim()?.takeIf { it.isNotBlank() && it != "UNKNOWN" }
            if (t == "RET") return call.result?.trim()?.takeIf { it.isNotBlank() && it != "UNKNOWN" }
            if (t.startsWith("PARAM:")) {
                val idx = t.substringAfter("PARAM:", missingDelimiterValue = "").toIntOrNull() ?: return null
                return call.args.getOrNull(idx)?.trim()?.takeIf { it.isNotBlank() && it != "UNKNOWN" }
            }

            val heap = parseHeapFieldToken(t) ?: return null
            val mappedBase = mapCalleeTokenToCallerToken(heap.baseToken, call, depth = depth + 1) ?: return null
            val mapped = buildHeapFieldToken(mappedBase, heap.fieldSuffix)
            return mapped.takeIf { it.isNotBlank() && it != "UNKNOWN" }
        }

        val isHeapToken = parseHeapFieldToken(token) != null
        val heapRootBase = if (isHeapToken) heapRootBaseToken(token) else null
        val heapCallKind = callKindForRootBase(heapRootBase)

        val paramIndex = token.substringAfter("PARAM:", missingDelimiterValue = "").toIntOrNull()
        val isParam = token.startsWith("PARAM:") && paramIndex != null && paramIndex >= 0
        val isReceiver = token == "THIS"
        val isReturn = token == "RET"

        if (!isParam && !isReceiver && !isReturn && heapCallKind == null) return emptyList()

        for (caller in incomingCallers) {
            val callerSummary = summaries.summaries[caller] ?: continue
            val directCallSites = callerSummary.calls.filter { it.calleeId == node.method.id }
            val edgeCallOffset = graph.callSites[CallEdge(caller = caller, callee = node.method)]?.startOffset
            val callSites =
                buildList {
                    addAll(directCallSites)
                    if (edgeCallOffset != null) {
                        for (call in callerSummary.calls) {
                            if (call.callOffset == edgeCallOffset) add(call)
                        }
                    }
                }.distinctBy { "${it.callOffset}:${it.calleeId}" }
            for (call in callSites) {
                when {
                    heapCallKind != null -> {
                        val mapped = mapCalleeTokenToCallerToken(token, call) ?: continue
                        out.add(
                            ValueFlowEdge(
                                kind = heapCallKind,
                                from = node,
                                to = ValueFlowNode(method = caller, token = mapped),
                                offset = call.callOffset,
                                details =
                                    when (heapCallKind) {
                                        ValueFlowEdgeKind.CALL_ARG -> {
                                            val idx =
                                                heapRootBase
                                                    ?.substringAfter("PARAM:", missingDelimiterValue = "")
                                                    ?.toIntOrNull()
                                                    ?.takeIf { it >= 0 }
                                            if (idx != null) "${caller.id} -> ${node.method.id} heap argIndex=$idx" else "${caller.id} -> ${node.method.id} heap arg"
                                        }
                                        ValueFlowEdgeKind.CALL_RECEIVER -> "${caller.id} -> ${node.method.id} heap receiver"
                                        ValueFlowEdgeKind.CALL_RETURN -> "${caller.id} <- ${node.method.id} heap return"
                                        else -> "${caller.id} -> ${node.method.id} heap"
                                    },
                            )
                        )
                    }

                    isParam -> {
                        val mapped = call.args.getOrNull(paramIndex) ?: "UNKNOWN"
                        out.add(
                            ValueFlowEdge(
                                kind = ValueFlowEdgeKind.CALL_ARG,
                                from = node,
                                to = ValueFlowNode(method = caller, token = mapped),
                                offset = call.callOffset,
                                details = "${caller.id} -> ${node.method.id} argIndex=$paramIndex",
                            )
                        )
                    }

                    isReceiver -> {
                        val mapped = call.receiver ?: "UNKNOWN"
                        out.add(
                            ValueFlowEdge(
                                kind = ValueFlowEdgeKind.CALL_RECEIVER,
                                from = node,
                                to = ValueFlowNode(method = caller, token = mapped),
                                offset = call.callOffset,
                                details = "${caller.id} -> ${node.method.id} receiver",
                            )
                        )
                    }

                    isReturn -> {
                        val mapped = call.result ?: "UNKNOWN"
                        out.add(
                            ValueFlowEdge(
                                kind = ValueFlowEdgeKind.CALL_RETURN,
                                from = node,
                                to = ValueFlowNode(method = caller, token = mapped),
                                offset = call.callOffset,
                                details = "${caller.id} <- ${node.method.id} return",
                            )
                        )
                    }
                }
            }
        }

        return out
    }

    private fun reconstructEdges(
        start: ValueFlowNode,
        end: ValueFlowNode,
        parents: Map<ValueFlowNode, Parent>,
    ): List<ValueFlowEdge> {
        val reversed = mutableListOf<ValueFlowEdge>()
        var cur = end
        while (cur != start) {
            val parent = parents[cur] ?: break
            reversed.add(parent.via)
            cur = parent.prev
        }
        return reversed.reversed()
    }

    private companion object {
        private const val CALL_RESULT_TOKEN_PREFIX = "CALLRET@"
        private const val BEAN_TOKEN_PREFIX = "BEAN:"
        private const val INJECT_TOKEN_PREFIX = "INJECT:"
        private const val ALLOC_TOKEN_PREFIX = "ALLOC:"
        private const val UNKNOWN_TOKEN_PREFIX = "UNKNOWN:"
    }
}
