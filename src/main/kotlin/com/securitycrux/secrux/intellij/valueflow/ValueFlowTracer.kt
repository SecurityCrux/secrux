package com.securitycrux.secrux.intellij.valueflow

import com.securitycrux.secrux.intellij.callgraph.CallEdge
import com.securitycrux.secrux.intellij.callgraph.CallGraph
import com.securitycrux.secrux.intellij.callgraph.MethodRef
import com.securitycrux.secrux.intellij.callgraph.TypeHierarchyIndex
import com.securitycrux.secrux.intellij.callgraph.callSiteOffsets
import java.util.ArrayDeque

enum class ValueFlowEdgeKind {
    ALIAS,
    COPY,
    LOAD,
    STORE,
    HEAP_STORE,
    HEAP_LOAD,
    CALL_ARG,
    CALL_RECEIVER,
    CALL_RETURN,
    CALL_RESULT,
    CALL_APPROX,
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
    private val pointsToIndex: PointsToIndex? = null,
) {
    private data class TraceState(
        val node: ValueFlowNode,
        val depth: Int,
        val contextOffset: Int?,
    )

    private data class Parent(
        val prev: ValueFlowNode,
        val via: ValueFlowEdge,
    )

    private fun normalizeContextOffsetForToken(token: String, contextOffset: Int?): Int? {
        val base = contextOffset ?: return null
        val tokenOffset = tokenOffset(token) ?: return base
        return maxOf(base, tokenOffset)
    }

    private fun tokenOffset(token: String): Int? {
        val t = token.trim()
        if (t.startsWith(CALL_RESULT_TOKEN_PREFIX)) {
            return t.substringAfter(CALL_RESULT_TOKEN_PREFIX, missingDelimiterValue = "").toIntOrNull()
        }
        if (t.startsWith(ALLOC_TOKEN_PREFIX)) {
            return t.substringAfterLast('@', missingDelimiterValue = "").toIntOrNull()
        }
        return null
    }

    private data class StoreSite(
        val method: MethodRef,
        val targetField: String,
        val baseToken: String,
        val fieldSuffix: String,
        val value: String,
        val offset: Int?,
    )

    private data class LoadSite(
        val method: MethodRef,
        val sourceField: String,
        val baseToken: String,
        val fieldSuffix: String,
        val target: String,
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

    private val loadSitesByFieldSuffix: Map<String, List<LoadSite>> =
        buildMap<String, MutableList<LoadSite>> {
            for ((method, summary) in summaries.summaries) {
                for (load in summary.loads) {
                    val sourceField = load.sourceField.trim().takeIf { it.isNotBlank() } ?: continue
                    val parsed = parseHeapFieldToken(sourceField) ?: continue
                    val target = load.target.trim().takeIf { it.isNotBlank() } ?: continue
                    getOrPut(parsed.fieldSuffix) { mutableListOf() }.add(
                        LoadSite(
                            method = method,
                            sourceField = sourceField,
                            baseToken = parsed.baseToken,
                            fieldSuffix = parsed.fieldSuffix,
                            target = target,
                            offset = load.offset,
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
        startOffset: Int? = null,
        maxDepth: Int = 10,
        maxStates: Int = 2_000,
        maxHeapWritersPerStep: Int = 25,
    ): ValueFlowTrace? =
        traceToRoots(
            startMethod = startMethod,
            startToken = startToken,
            startOffset = startOffset,
            maxDepth = maxDepth,
            maxStates = maxStates,
            maxHeapWritersPerStep = maxHeapWritersPerStep,
            maxTraces = 1,
        ).firstOrNull()

    fun traceToRoots(
        startMethod: MethodRef,
        startToken: String,
        startOffset: Int? = null,
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
        val visitedMaxOffset = hashMapOf<ValueFlowNode, Int>()
        val parents = hashMapOf<ValueFlowNode, Parent>()

        fun offsetKey(offset: Int?): Int = offset ?: Int.MAX_VALUE

        fun enqueue(next: ValueFlowNode, depth: Int, contextOffset: Int?, via: ValueFlowEdge) {
            if (next.token.isBlank()) return
            if (visitedMaxOffset.size >= maxStates) return
            val normalizedOffset = normalizeContextOffsetForToken(next.token, contextOffset)
            val nextKey = offsetKey(normalizedOffset)
            val existing = visitedMaxOffset[next]
            if (existing != null && existing >= nextKey) return
            visitedMaxOffset[next] = maxOf(existing ?: Int.MIN_VALUE, nextKey)
            parents[next] = Parent(prev = via.from, via = via)
            queue.addLast(TraceState(node = next, depth = depth, contextOffset = normalizedOffset))
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

        val normalizedStartOffset = normalizeContextOffsetForToken(start.token, startOffset)
        visitedMaxOffset[start] = offsetKey(normalizedStartOffset)
        queue.addLast(TraceState(node = start, depth = 0, contextOffset = normalizedStartOffset))

        val candidates = linkedMapOf<ValueFlowNode, Pair<Int, Int>>()

        while (queue.isNotEmpty()) {
            val (current, depth, currentOffset) = queue.removeFirst()

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
                expandCopiesBackward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandLoadsBackward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandStoresBackward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandCallResultsToCallee(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandCallResultApproxToInputs(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
            }
            expandInjectEdges(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            expandCallToCaller(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            expandHeapStores(current, maxHeapWritersPerStep).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
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

    /**
     * "Round-trip" value-flow: first do a backward trace (sink -> roots), then try to recover a readable
     * forward chain by running a bounded forward propagation from the backward root back towards the sink.
     *
     * Output traces remain in the same format as [traceToRoots] (start=sink, end=root, edges are still in
     * `target <= source` orientation) so existing UI/evidence code can keep working.
     */
    fun traceToRootsRoundTrip(
        startMethod: MethodRef,
        startToken: String,
        startOffset: Int? = null,
        maxDepth: Int = 10,
        maxStates: Int = 2_000,
        maxHeapWritersPerStep: Int = 25,
        maxTraces: Int = 3,
        forwardMaxDepth: Int = 16,
        forwardMaxStates: Int = 4_000,
        forwardMaxHeapReadersPerStep: Int = 25,
    ): List<ValueFlowTrace> {
        if (startToken.isBlank()) return emptyList()

        val backwardTraces =
            traceToRoots(
                startMethod = startMethod,
                startToken = startToken,
                startOffset = startOffset,
                maxDepth = maxDepth,
                maxStates = maxStates,
                maxHeapWritersPerStep = maxHeapWritersPerStep,
                maxTraces = maxTraces,
            )

        val sink = ValueFlowNode(method = startMethod, token = startToken)
        val safeForwardDepth = forwardMaxDepth.coerceIn(1, 50)
        val safeForwardStates = forwardMaxStates.coerceIn(200, 50_000)
        val safeForwardReaders = forwardMaxHeapReadersPerStep.coerceIn(1, 200)

        fun invert(edge: ValueFlowEdge): ValueFlowEdge =
            ValueFlowEdge(
                kind = edge.kind,
                from = edge.to,
                to = edge.from,
                offset = edge.offset,
                filePath = edge.filePath,
                details = edge.details,
            )

        fun tryForwardTaint(root: ValueFlowNode): ValueFlowTrace? {
            val parents =
                findForwardPath(
                    start = root,
                    target = sink,
                    maxDepth = safeForwardDepth,
                    maxStates = safeForwardStates,
                    maxHeapReadersPerStep = safeForwardReaders,
                ) ?: return null
            val forwardEdges = reconstructEdges(start = root, end = sink, parents = parents)
            val backwardEdges = forwardEdges.asReversed().map(::invert)
            return ValueFlowTrace(start = sink, end = root, edges = backwardEdges)
        }

        if (backwardTraces.isNotEmpty()) {
            return backwardTraces.map { trace ->
                tryForwardTaint(trace.end) ?: trace
            }
        }

        val entrypointTrace =
            traceFromEntryPoints(
                sink = sink,
                maxDepth = safeForwardDepth,
                maxStates = safeForwardStates,
                maxHeapReadersPerStep = safeForwardReaders,
            ) ?: return emptyList()

        return listOf(entrypointTrace)
    }

    /**
     * Inter-procedural value-flow tracing constrained to a single call chain (root -> ... -> sinkMethod).
     *
     * This is designed to *validate* whether a taint path can be established along a specific call chain,
     * instead of exploring the whole project graph.
     *
     * Constraints:
     * - Cross-method propagation is only allowed between adjacent methods on the chain.
     * - Calls not on the chain are treated as "external/unmodeled" for `CALL_APPROX`, even if we have summaries,
     *   so we can keep propagation inside the chain methods without jumping into unrelated callees.
     * - Heap store/load hops are limited to methods on the chain.
     *
     * Returns traces that end at the chain root method only; empty means "not verified".
     */
    fun traceToChainRoot(
        chainMethodsOrdered: List<MethodRef>,
        startToken: String,
        startOffset: Int? = null,
        maxDepth: Int = 12,
        maxStates: Int = 1_500,
        maxHeapWritersPerStep: Int = 25,
        maxTraces: Int = 1,
    ): List<ValueFlowTrace> {
        val methods = chainMethodsOrdered.distinct()
        if (methods.isEmpty()) return emptyList()
        if (startToken.isBlank()) return emptyList()

        val rootMethod = methods.first()
        val sinkMethod = methods.last()

        val allowedMethods = methods.toSet()

        val allowedCalleesByCaller = hashMapOf<MethodRef, Set<MethodRef>>()
        val allowedCallersByCallee = hashMapOf<MethodRef, Set<MethodRef>>()
        val allowedCallOffsets = hashMapOf<CallEdge, Set<Int>>()

        for (i in 0 until methods.lastIndex) {
            val caller = methods[i]
            val callee = methods[i + 1]
            allowedCalleesByCaller[caller] = setOf(callee)
            allowedCallersByCallee[callee] = setOf(caller)
            val edge = CallEdge(caller = caller, callee = callee)
            allowedCallOffsets[edge] = graph.callSiteOffsets(edge)
        }

        val candidates =
            traceToMethodInScope(
                startMethod = sinkMethod,
                startToken = startToken,
                startOffset = startOffset,
                targetMethod = rootMethod,
                allowedMethods = allowedMethods,
                allowedCallersByCallee = allowedCallersByCallee,
                allowedCalleesByCaller = allowedCalleesByCaller,
                allowedCallOffsets = allowedCallOffsets,
                treatOutOfScopeCallsAsExternal = true,
                maxDepth = maxDepth,
                maxStates = maxStates,
                maxHeapWritersPerStep = maxHeapWritersPerStep,
                maxTraces = maxTraces,
            )

        return candidates.filter { it.end.method == rootMethod }
    }

    private fun traceToMethodInScope(
        startMethod: MethodRef,
        startToken: String,
        startOffset: Int?,
        targetMethod: MethodRef,
        allowedMethods: Set<MethodRef>,
        allowedCallersByCallee: Map<MethodRef, Set<MethodRef>>,
        allowedCalleesByCaller: Map<MethodRef, Set<MethodRef>>,
        allowedCallOffsets: Map<CallEdge, Set<Int>>,
        treatOutOfScopeCallsAsExternal: Boolean,
        maxDepth: Int,
        maxStates: Int,
        maxHeapWritersPerStep: Int,
        maxTraces: Int,
    ): List<ValueFlowTrace> {
        if (startToken.isBlank()) return emptyList()
        if (maxDepth <= 0 || maxStates <= 0) return emptyList()
        if (maxTraces <= 0) return emptyList()

        val safeMaxTraces = maxTraces.coerceIn(1, 20)

        val start = ValueFlowNode(method = startMethod, token = startToken)
        if (start.method !in allowedMethods) return emptyList()
        if (targetMethod !in allowedMethods) return emptyList()

        if (startMethod == targetMethod) {
            return listOf(ValueFlowTrace(start = start, end = start, edges = emptyList()))
        }

        val queue = ArrayDeque<TraceState>()
        val visitedMaxOffset = hashMapOf<ValueFlowNode, Int>()
        val parents = hashMapOf<ValueFlowNode, Parent>()
        val hits = mutableListOf<ValueFlowNode>()

        fun offsetKey(offset: Int?): Int = offset ?: Int.MAX_VALUE

        fun isAllowedOffset(edge: CallEdge, offset: Int?): Boolean {
            val allowed = allowedCallOffsets[edge].orEmpty()
            if (allowed.isEmpty()) return true
            val off = offset ?: return true
            return off in allowed
        }

        fun enqueue(next: ValueFlowNode, depth: Int, contextOffset: Int?, via: ValueFlowEdge) {
            if (next.token.isBlank()) return
            if (next.method !in allowedMethods) return
            if (visitedMaxOffset.size >= maxStates) return
            val normalizedOffset = normalizeContextOffsetForToken(next.token, contextOffset)
            val nextKey = offsetKey(normalizedOffset)
            val existing = visitedMaxOffset[next]
            if (existing != null && existing >= nextKey) return
            visitedMaxOffset[next] = maxOf(existing ?: Int.MIN_VALUE, nextKey)
            parents[next] = Parent(prev = via.from, via = via)
            queue.addLast(TraceState(node = next, depth = depth, contextOffset = normalizedOffset))
        }

        fun filterEdges(edges: List<ValueFlowEdge>): List<ValueFlowEdge> {
            if (edges.isEmpty()) return edges
            return edges.filter { it.to.method in allowedMethods }
        }

        fun expandCallToCallerScoped(node: ValueFlowNode): List<ValueFlowEdge> {
            val raw = expandCallToCaller(node)
            if (raw.isEmpty()) return raw
            return raw.filter { edge ->
                val caller = edge.to.method
                val callee = edge.from.method
                if (caller !in allowedMethods) return@filter false
                if (callee !in allowedMethods) return@filter false
                val allowedCallers = allowedCallersByCallee[callee].orEmpty()
                if (caller !in allowedCallers) return@filter false
                val callEdge = CallEdge(caller = caller, callee = callee)
                isAllowedOffset(callEdge, edge.offset)
            }
        }

        fun expandCallResultsToCalleeScoped(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
            val raw = expandCallResultsToCallee(node, summary, contextOffset)
            if (raw.isEmpty()) return raw
            return raw.filter { edge ->
                val from = edge.from.method
                val to = edge.to.method
                if (from !in allowedMethods) return@filter false
                if (to !in allowedMethods) return@filter false
                if (from == to) return@filter true
                val allowedCallees = allowedCalleesByCaller[from].orEmpty()
                if (to !in allowedCallees) return@filter false
                val callEdge = CallEdge(caller = from, callee = to)
                isAllowedOffset(callEdge, edge.offset)
            }
        }

        fun expandCallResultApproxToInputsScoped(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
            val token = node.token.trim()
            if (token.isBlank() || isUnknownToken(token)) return emptyList()

            fun isExternalOrUnmodeledForScope(call: CallSiteSummary): Boolean {
                if (!treatOutOfScopeCallsAsExternal) return false
                val targets = possibleCallTargets(caller = node.method, declaredCalleeId = call.calleeId, callOffset = call.callOffset)
                if (targets.isEmpty()) return true

                val allowedCallees = allowedCalleesByCaller[node.method].orEmpty()
                if (allowedCallees.isEmpty()) return true
                if (targets.none { it in allowedCallees }) return true

                val inScope = targets.filter { it in allowedMethods }
                if (inScope.isEmpty()) return true
                return inScope.none { summaries.summaries.containsKey(it) }
            }

            val calls =
                when {
                    token.startsWith(CALL_RESULT_TOKEN_PREFIX) -> {
                        val callOffset = token.substringAfter(CALL_RESULT_TOKEN_PREFIX, missingDelimiterValue = "").toIntOrNull()
                        if (callOffset != null) {
                            summary.calls.filter { it.callOffset == callOffset }
                        } else {
                            emptyList()
                        }
                    }

                    else -> summary.calls.filter { it.result?.trim() == token }
                }

            if (calls.isEmpty()) return emptyList()

            val out = mutableListOf<ValueFlowEdge>()
            val seen = hashSetOf<String>()

            fun addEdge(toToken: String, callOffset: Int?, details: String) {
                if (toToken.isBlank() || isUnknownToken(toToken)) return
                if (toToken == token) return
                val key = "${callOffset ?: -1}:$toToken:$details"
                if (!seen.add(key)) return
                out.add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.CALL_APPROX,
                        from = node,
                        to = ValueFlowNode(method = node.method, token = toToken),
                        offset = callOffset,
                        details = details,
                    )
                )
            }

            for (call in calls) {
                val callOffset = call.callOffset
                if (contextOffset != null && callOffset != null && callOffset > contextOffset) continue
                if (!isExternalOrUnmodeledForScope(call)) continue

                val callee = call.calleeId ?: "UNKNOWN_CALLEE"
                addEdge(
                    toToken = call.receiver?.trim().orEmpty(),
                    callOffset = callOffset,
                    details = "external-call $callee receiver -> result",
                )
                for ((idx, arg) in call.args.withIndex()) {
                    addEdge(
                        toToken = arg.trim(),
                        callOffset = callOffset,
                        details = "external-call $callee argIndex=$idx -> result",
                    )
                }
            }

            return out
        }

        val normalizedStartOffset = normalizeContextOffsetForToken(start.token, startOffset)
        visitedMaxOffset[start] = offsetKey(normalizedStartOffset)
        queue.addLast(TraceState(node = start, depth = 0, contextOffset = normalizedStartOffset))

        while (queue.isNotEmpty()) {
            val (current, depth, currentOffset) = queue.removeFirst()

            if (current != start && current.method == targetMethod) {
                hits.add(current)
                if (hits.size >= safeMaxTraces) break
            }

            if (depth >= maxDepth) continue

            val summary = summaries.summaries[current.method]
            if (summary != null) {
                filterEdges(expandCopiesBackward(current, summary, currentOffset)).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                filterEdges(expandLoadsBackward(current, summary, currentOffset)).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                filterEdges(expandStoresBackward(current, summary, currentOffset)).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                filterEdges(expandCallResultsToCalleeScoped(current, summary, currentOffset)).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                filterEdges(expandCallResultApproxToInputsScoped(current, summary, currentOffset)).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
            }

            filterEdges(expandInjectEdges(current)).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            filterEdges(expandCallToCallerScoped(current)).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            filterEdges(expandHeapStores(current, maxHeapWritersPerStep)).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
        }

        if (hits.isEmpty()) return emptyList()

        return hits.map { end ->
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

    private fun ownerTypeFromFieldSuffix(fieldSuffix: String): String? {
        val owner = fieldSuffix.substringBefore('#', missingDelimiterValue = "").trim()
        return owner.takeIf { it.isNotBlank() }
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

    private fun expandHeapBaseAliases(node: ValueFlowNode, summary: MethodSummary): List<ValueFlowEdge> {
        val token = node.token
        val heap = parseHeapFieldToken(token) ?: return emptyList()

        val out = mutableListOf<ValueFlowEdge>()
        for (copy in summary.aliases) {
            val otherBase =
                when {
                    copy.left == heap.baseToken -> copy.right
                    copy.right == heap.baseToken -> copy.left
                    else -> null
                } ?: continue
            if (otherBase == "UNKNOWN") continue
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.ALIAS,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = buildHeapFieldToken(otherBase, heap.fieldSuffix)),
                    offset = copy.offset,
                    details = "heapBase ${heap.baseToken} <-> $otherBase",
                )
            )
        }
        return out
    }

    private fun expandCopiesBackward(
        node: ValueFlowNode,
        summary: MethodSummary,
        contextOffset: Int?,
        maxEdgesPerToken: Int = 3,
    ): List<ValueFlowEdge> {
        val token = node.token
        if (parseHeapFieldToken(token) != null) {
            return expandHeapBaseAliases(node, summary)
        }

        fun withinOffset(edgeOffset: Int?): Boolean =
            contextOffset == null || edgeOffset == null || edgeOffset <= contextOffset

        val candidates =
            summary.aliases
                .asSequence()
                .filter { it.left == token }
                .filter { withinOffset(it.offset) }
                .toList()

        if (candidates.isEmpty()) return emptyList()

        val safeMax = maxEdgesPerToken.coerceIn(1, 50)
        val ordered =
            candidates
                .sortedWith(
                    compareByDescending<AliasEdge> { it.offset ?: Int.MIN_VALUE }
                        .thenBy { it.right }
                )
                .take(safeMax)

        return ordered.map { copy ->
            ValueFlowEdge(
                kind = ValueFlowEdgeKind.COPY,
                from = node,
                to = ValueFlowNode(method = node.method, token = copy.right),
                offset = copy.offset,
                details = "copy ${copy.left} <= ${copy.right}",
            )
        }
    }

    private fun expandCopiesForward(
        node: ValueFlowNode,
        summary: MethodSummary,
        contextOffset: Int?,
        maxEdgesPerToken: Int = 6,
    ): List<ValueFlowEdge> {
        val token = node.token
        if (parseHeapFieldToken(token) != null) {
            return expandHeapBaseAliases(node, summary)
        }

        fun withinOffset(edgeOffset: Int?): Boolean =
            contextOffset == null || edgeOffset == null || edgeOffset >= contextOffset

        val candidates =
            summary.aliases
                .asSequence()
                .filter { it.right == token }
                .filter { withinOffset(it.offset) }
                .toList()

        if (candidates.isEmpty()) return emptyList()

        val safeMax = maxEdgesPerToken.coerceIn(1, 200)
        val ordered =
            candidates
                .sortedWith(
                    compareBy<AliasEdge> { it.offset ?: Int.MAX_VALUE }
                        .thenBy { it.left }
                )
                .take(safeMax)

        return ordered.map { copy ->
            ValueFlowEdge(
                kind = ValueFlowEdgeKind.COPY,
                from = node,
                to = ValueFlowNode(method = node.method, token = copy.left),
                offset = copy.offset,
                details = "copy ${copy.left} <= ${copy.right}",
            )
        }
    }

    private fun expandLoadsBackward(
        node: ValueFlowNode,
        summary: MethodSummary,
        contextOffset: Int?,
        maxEdgesPerToken: Int = 3,
    ): List<ValueFlowEdge> {
        val token = node.token
        val candidates =
            summary.loads
                .asSequence()
                .filter { it.target == token }
                .filter { contextOffset == null || it.offset == null || it.offset <= contextOffset }
                .toList()

        if (candidates.isEmpty()) return emptyList()

        val safeMax = maxEdgesPerToken.coerceIn(1, 50)
        val ordered =
            candidates
                .sortedWith(compareByDescending<FieldLoad> { it.offset ?: Int.MIN_VALUE })
                .take(safeMax)

        return ordered.map { load ->
            ValueFlowEdge(
                kind = ValueFlowEdgeKind.LOAD,
                from = node,
                to = ValueFlowNode(method = node.method, token = load.sourceField),
                offset = load.offset,
            )
        }
    }

    private fun expandCallResultsToCallee(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
        val token = node.token
        val out = mutableListOf<ValueFlowEdge>()

        fun addTargets(call: CallSiteSummary) {
            val callOffset = call.callOffset
            if (contextOffset != null && callOffset != null && callOffset > contextOffset) return
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
            val targets =
                possibleCallTargets(caller = node.method, declaredCalleeId = call.calleeId, callOffset = callOffset)
                    .filter { summaries.summaries.containsKey(it) }
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

        val calls =
            when {
                token.startsWith(CALL_RESULT_TOKEN_PREFIX) -> {
                    val callOffset = token.substringAfter(CALL_RESULT_TOKEN_PREFIX).toIntOrNull()
                    if (callOffset != null) {
                        summary.calls.filter { it.callOffset == callOffset }
                    } else {
                        emptyList()
                    }
                }

                else -> summary.calls.filter { it.result?.trim() == token }
            }

        for (call in calls) {
            addTargets(call)
        }

        return out
    }

    private fun expandCallResultApproxToInputs(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
        val token = node.token.trim()
        if (token.isBlank() || isUnknownToken(token)) return emptyList()

        fun isExternalOrUnmodeled(call: CallSiteSummary): Boolean {
            val targets = possibleCallTargets(caller = node.method, declaredCalleeId = call.calleeId, callOffset = call.callOffset)
            if (targets.isEmpty()) return true
            return targets.none { summaries.summaries.containsKey(it) }
        }

        val calls =
            when {
                token.startsWith(CALL_RESULT_TOKEN_PREFIX) -> {
                    val callOffset = token.substringAfter(CALL_RESULT_TOKEN_PREFIX, missingDelimiterValue = "").toIntOrNull()
                    if (callOffset != null) {
                        summary.calls.filter { it.callOffset == callOffset }
                    } else {
                        emptyList()
                    }
                }

                else -> summary.calls.filter { it.result?.trim() == token }
            }

        if (calls.isEmpty()) return emptyList()

        val out = mutableListOf<ValueFlowEdge>()
        val seen = hashSetOf<String>()

        fun addEdge(toToken: String, callOffset: Int?, details: String) {
            if (toToken.isBlank() || isUnknownToken(toToken)) return
            if (toToken == token) return
            val key = "${callOffset ?: -1}:$toToken:$details"
            if (!seen.add(key)) return
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.CALL_APPROX,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = toToken),
                    offset = callOffset,
                    details = details,
                )
            )
        }

        for (call in calls) {
            val callOffset = call.callOffset
            if (contextOffset != null && callOffset != null && callOffset > contextOffset) continue
            if (!isExternalOrUnmodeled(call)) continue
            val callee = call.calleeId ?: "UNKNOWN_CALLEE"

            call.receiver?.trim()?.let { recv ->
                addEdge(
                    toToken = recv,
                    callOffset = callOffset,
                    details = "external-call $callee receiver -> result",
                )
            }

            for ((idx, arg) in call.args.withIndex()) {
                addEdge(
                    toToken = arg.trim(),
                    callOffset = callOffset,
                    details = "external-call $callee argIndex=$idx -> result",
                )
            }
        }

        return out
    }

    private fun expandAllocForward(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
        val token = node.token.trim()
        if (!token.startsWith(ALLOC_TOKEN_PREFIX)) return emptyList()

        val suffix = token.removePrefix(ALLOC_TOKEN_PREFIX)
        val allocOffset = suffix.substringAfterLast('@', missingDelimiterValue = "").toIntOrNull() ?: return emptyList()
        if (contextOffset != null && allocOffset < contextOffset) return emptyList()
        val callResultToken = "$CALL_RESULT_TOKEN_PREFIX$allocOffset"

        val outputs =
            buildList {
                add(callResultToken)
                for (call in summary.calls) {
                    if (call.callOffset != allocOffset) continue
                    call.result?.trim()?.takeIf { it.isNotBlank() && it != "UNKNOWN" }?.let { add(it) }
                }
            }.distinct()

        if (outputs.isEmpty()) return emptyList()

        return outputs.map { outToken ->
            ValueFlowEdge(
                kind = ValueFlowEdgeKind.CALL_RESULT,
                from = node,
                to = ValueFlowNode(method = node.method, token = outToken),
                offset = allocOffset,
                details = "alloc -> $outToken",
            )
        }
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
                val locs = graph.callSites[edge].orEmpty()
                if (locs.any { it.startOffset == callOffset }) {
                    out.add(callee)
                }
            }
        }

        return out.toList()
    }

    private fun expandStoresBackward(
        node: ValueFlowNode,
        summary: MethodSummary,
        contextOffset: Int?,
        maxEdgesPerToken: Int = 3,
    ): List<ValueFlowEdge> {
        val token = node.token
        val candidates =
            summary.stores
                .asSequence()
                .filter { it.targetField == token }
                .filter { contextOffset == null || it.offset == null || it.offset <= contextOffset }
                .toList()

        if (candidates.isEmpty()) return emptyList()

        val safeMax = maxEdgesPerToken.coerceIn(1, 50)
        val ordered =
            candidates
                .sortedWith(compareByDescending<FieldStore> { it.offset ?: Int.MIN_VALUE })
                .take(safeMax)

        return ordered.map { store ->
            ValueFlowEdge(
                kind = ValueFlowEdgeKind.STORE,
                from = node,
                to = ValueFlowNode(method = node.method, token = store.value),
                offset = store.offset,
            )
        }
    }

    private fun expandStoresForward(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
        val token = node.token
        if (token.isBlank() || isUnknownToken(token)) return emptyList()
        val out = mutableListOf<ValueFlowEdge>()
        for (store in summary.stores) {
            if (store.value != token) continue
            if (contextOffset != null && store.offset != null && store.offset < contextOffset) continue
            val target = store.targetField.trim().takeIf { it.isNotBlank() } ?: continue
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.STORE,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = target),
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

        val expectedOwnerType = ownerTypeFromFieldSuffix(heap.fieldSuffix)

        val baseNode = ValueFlowNode(method = node.method, token = heap.baseToken)
        val basePointsTo = pointsToRoots(baseNode)
        val baseFilteredRoots =
            filterPointsToRootsByExpectedType(basePointsTo.roots, expectedOwnerType)
        val baseConcreteRoots = baseFilteredRoots.filterNot(::isUnknownToken).toSet()

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
            val storeConcreteRoots = storeFilteredRoots.filterNot(::isUnknownToken).toSet()

            val bonus =
                (if (site.method in incomingCallers) 100 else 0) +
                    (if (site.method.classFqn == node.method.classFqn) 10 else 0)

            val commonConcrete = baseConcreteRoots.intersect(storeConcreteRoots)
            if (commonConcrete.isNotEmpty()) {
                scored.add(
                    ScoredStore(
                        site = site,
                        score = bonus + commonConcrete.size,
                        reason = "points-to common=${commonConcrete.take(3)}",
                    )
                )
                continue
            }

            if (basePointsTo.truncated || storePointsTo.truncated || baseConcreteRoots.isEmpty() || storeConcreteRoots.isEmpty()) {
                scored.add(
                    ScoredStore(
                        site = site,
                        score = bonus,
                        reason = "points-to unknown",
                    )
                )
            }
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

    private fun expandHeapLoadsForward(node: ValueFlowNode, maxReaders: Int): List<ValueFlowEdge> {
        val token = node.token
        val heap = parseHeapFieldToken(token) ?: return emptyList()

        val readers = loadSitesByFieldSuffix[heap.fieldSuffix].orEmpty()
        if (readers.isEmpty()) return emptyList()

        val safeMaxReaders = maxReaders.coerceIn(1, 200)
        val outgoingCallees = graph.outgoing[node.method].orEmpty()
        val incomingCallers = graph.incoming[node.method].orEmpty()

        val expectedOwnerType = ownerTypeFromFieldSuffix(heap.fieldSuffix)

        val baseNode = ValueFlowNode(method = node.method, token = heap.baseToken)
        val basePointsTo = pointsToRoots(baseNode)
        val baseFilteredRoots =
            filterPointsToRootsByExpectedType(basePointsTo.roots, expectedOwnerType)
        val baseConcreteRoots = baseFilteredRoots.filterNot(::isUnknownToken).toSet()

        data class ScoredLoad(
            val site: LoadSite,
            val score: Int,
            val reason: String?,
        )

        val orderedCandidates =
            readers
                .asSequence()
                .filter { it.method != node.method }
                .sortedWith(
                    compareByDescending<LoadSite> { it.method in outgoingCallees }
                        .thenByDescending { it.method in incomingCallers }
                        .thenByDescending { it.method.classFqn == node.method.classFqn }
                )
                .toList()

        val maxCandidatesToCheck = (safeMaxReaders * 25).coerceIn(safeMaxReaders, 2_000)
        val candidatesToCheck = orderedCandidates.take(maxCandidatesToCheck)
        val candidateOverflow = orderedCandidates.size > candidatesToCheck.size

        val scored = mutableListOf<ScoredLoad>()
        for (site in candidatesToCheck) {
            val loadBaseNode = ValueFlowNode(method = site.method, token = site.baseToken)
            val loadPointsTo = pointsToRoots(loadBaseNode)
            val loadFilteredRoots =
                filterPointsToRootsByExpectedType(loadPointsTo.roots, expectedOwnerType)
            val loadConcreteRoots = loadFilteredRoots.filterNot(::isUnknownToken).toSet()

            val bonus =
                (if (site.method in outgoingCallees) 100 else 0) +
                    (if (site.method in incomingCallers) 50 else 0) +
                    (if (site.method.classFqn == node.method.classFqn) 10 else 0)

            val commonConcrete = baseConcreteRoots.intersect(loadConcreteRoots)
            if (commonConcrete.isNotEmpty()) {
                scored.add(
                    ScoredLoad(
                        site = site,
                        score = bonus + commonConcrete.size,
                        reason = "points-to common=${commonConcrete.take(3)}",
                    )
                )
                continue
            }

            if (basePointsTo.truncated || loadPointsTo.truncated || baseConcreteRoots.isEmpty() || loadConcreteRoots.isEmpty()) {
                scored.add(
                    ScoredLoad(
                        site = site,
                        score = bonus,
                        reason = "points-to unknown",
                    )
                )
            }
        }

        val prioritized =
            scored
                .sortedByDescending { it.score }
                .take(safeMaxReaders)
                .toList()

        val droppedByBudget = (scored.size - prioritized.size).coerceAtLeast(0)
        val omittedByCandidateCap = if (candidateOverflow) orderedCandidates.size - candidatesToCheck.size else 0
        val omittedTotal = droppedByBudget + omittedByCandidateCap
        val truncated = omittedTotal > 0

        return buildList {
            addAll(
                prioritized.map { item ->
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.HEAP_LOAD,
                        from = node,
                        to = ValueFlowNode(method = item.site.method, token = item.site.sourceField),
                        offset = item.site.offset,
                        filePath = graph.methods[item.site.method]?.file?.path,
                        details = token + item.reason?.let { " ($it)" }.orEmpty(),
                    )
                }
            )
            if (truncated) {
                add(
                    ValueFlowEdge(
                        kind = ValueFlowEdgeKind.HEAP_LOAD,
                        from = node,
                        to =
                            ValueFlowNode(
                                method = node.method,
                                token = "${UNKNOWN_TOKEN_PREFIX}HEAP_LOAD_TRUNCATED:+$omittedTotal",
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

        pointsToIndex?.query(method = node.method, token = token)?.let { indexed ->
            pointsToCache[key] = indexed
            return indexed
        }

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
                expandCopiesBackward(current, summary, contextOffset = null).forEach { edge -> enqueue(edge.to, depth + 1) }
                expandLoadsBackward(current, summary, contextOffset = null).forEach { edge -> enqueue(edge.to, depth + 1) }
                expandStoresBackward(current, summary, contextOffset = null).forEach { edge -> enqueue(edge.to, depth + 1) }
                expandCallResultsToCallee(current, summary, contextOffset = null).forEach { edge -> enqueue(edge.to, depth + 1) }
                expandCallResultApproxToInputs(current, summary, contextOffset = null).forEach { edge -> enqueue(edge.to, depth + 1) }
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
            val edgeCallOffsets =
                graph.callSites[CallEdge(caller = caller, callee = node.method)]
                    ?.mapTo(hashSetOf()) { it.startOffset }
                    .orEmpty()
            val callSites =
                buildList {
                    addAll(directCallSites)
                    if (edgeCallOffsets.isNotEmpty()) {
                        for (call in callerSummary.calls) {
                            val offset = call.callOffset ?: continue
                            if (offset in edgeCallOffsets) add(call)
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

    private fun expandLoadsForward(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
        val token = node.token
        if (token.isBlank() || isUnknownToken(token)) return emptyList()
        val out = mutableListOf<ValueFlowEdge>()
        for (load in summary.loads) {
            if (load.sourceField != token) continue
            if (contextOffset != null && load.offset != null && load.offset < contextOffset) continue
            val target = load.target.trim().takeIf { it.isNotBlank() } ?: continue
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.LOAD,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = target),
                    offset = load.offset,
                )
            )
        }
        return out
    }

    private fun expandCallFromCaller(
        node: ValueFlowNode,
        summary: MethodSummary,
        contextOffset: Int?,
        maxTargetsPerCall: Int = 50,
    ): List<ValueFlowEdge> {
        val token = node.token
        if (token.isBlank() || isUnknownToken(token)) return emptyList()

        val out = mutableListOf<ValueFlowEdge>()
        val safeMaxTargets = maxTargetsPerCall.coerceIn(1, 200)

        for (call in summary.calls) {
            val callOffset = call.callOffset
            if (contextOffset != null && callOffset != null && callOffset < contextOffset) continue
            val targets =
                possibleCallTargets(
                    caller = node.method,
                    declaredCalleeId = call.calleeId,
                    callOffset = callOffset,
                    maxTargets = safeMaxTargets,
                )
                    .filter { summaries.summaries.containsKey(it) }

            if (targets.isEmpty()) continue

            if (call.receiver == token) {
                for (callee in targets) {
                    out.add(
                        ValueFlowEdge(
                            kind = ValueFlowEdgeKind.CALL_RECEIVER,
                            from = node,
                            to = ValueFlowNode(method = callee, token = "THIS"),
                            offset = callOffset,
                            details = "${node.method.id} -> ${callee.id} receiver",
                        )
                    )
                }
            }

            for ((idx, arg) in call.args.withIndex()) {
                if (arg != token) continue
                for (callee in targets) {
                    out.add(
                        ValueFlowEdge(
                            kind = ValueFlowEdgeKind.CALL_ARG,
                            from = node,
                            to = ValueFlowNode(method = callee, token = "PARAM:$idx"),
                            offset = callOffset,
                            details = "${node.method.id} -> ${callee.id} argIndex=$idx",
                        )
                    )
                }
            }
        }

        return out
    }

    private fun expandCallInputsApproxToResultsForward(node: ValueFlowNode, summary: MethodSummary, contextOffset: Int?): List<ValueFlowEdge> {
        val token = node.token.trim()
        if (token.isBlank() || isUnknownToken(token)) return emptyList()

        fun isExternalOrUnmodeled(call: CallSiteSummary): Boolean {
            val targets = possibleCallTargets(caller = node.method, declaredCalleeId = call.calleeId, callOffset = call.callOffset)
            if (targets.isEmpty()) return true
            return targets.none { summaries.summaries.containsKey(it) }
        }

        val out = mutableListOf<ValueFlowEdge>()
        val seen = hashSetOf<String>()

        fun addEdge(toToken: String, callOffset: Int?, details: String) {
            if (toToken.isBlank() || isUnknownToken(toToken)) return
            if (toToken == token) return
            val key = "${callOffset ?: -1}:$toToken:$details"
            if (!seen.add(key)) return
            out.add(
                ValueFlowEdge(
                    kind = ValueFlowEdgeKind.CALL_APPROX,
                    from = node,
                    to = ValueFlowNode(method = node.method, token = toToken),
                    offset = callOffset,
                    details = details,
                )
            )
        }

        for (call in summary.calls) {
            val recvMatches = call.receiver?.trim() == token
            val argIndex = call.args.indexOfFirst { it.trim() == token }
            if (!recvMatches && argIndex < 0) continue
            if (!isExternalOrUnmodeled(call)) continue

            val callOffset = call.callOffset
            if (contextOffset != null && callOffset != null && callOffset < contextOffset) continue
            val callee = call.calleeId ?: "UNKNOWN_CALLEE"
            val inputTag =
                when {
                    recvMatches -> "receiver"
                    else -> "argIndex=$argIndex"
                }

            val resultToken = call.result?.trim()?.takeIf { it.isNotBlank() && !isUnknownToken(it) }
            if (resultToken != null) {
                addEdge(
                    toToken = resultToken,
                    callOffset = callOffset,
                    details = "external-call $callee $inputTag -> result",
                )
            } else if (callOffset != null) {
                addEdge(
                    toToken = "$CALL_RESULT_TOKEN_PREFIX$callOffset",
                    callOffset = callOffset,
                    details = "external-call $callee $inputTag -> result",
                )
            }
        }

        return out
    }

    private fun expandReturnToCallerForward(node: ValueFlowNode): List<ValueFlowEdge> {
        if (node.token != "RET") return emptyList()

        val incomingCallers = graph.incoming[node.method].orEmpty()
        if (incomingCallers.isEmpty()) return emptyList()

        val out = mutableListOf<ValueFlowEdge>()
        for (caller in incomingCallers) {
            val callerSummary = summaries.summaries[caller] ?: continue
            val directCallSites = callerSummary.calls.filter { it.calleeId == node.method.id }
            val edgeCallOffsets =
                graph.callSites[CallEdge(caller = caller, callee = node.method)]
                    ?.mapTo(hashSetOf()) { it.startOffset }
                    .orEmpty()
            val callSites =
                buildList {
                    addAll(directCallSites)
                    if (edgeCallOffsets.isNotEmpty()) {
                        for (call in callerSummary.calls) {
                            val offset = call.callOffset ?: continue
                            if (offset in edgeCallOffsets) add(call)
                        }
                    }
                }.distinctBy { "${it.callOffset}:${it.calleeId}" }

            for (call in callSites) {
                val mapped = call.result?.trim()?.takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: continue
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

        return out
    }

    private fun ownerClassFromFieldKey(token: String): String? {
        if (!token.startsWith("THIS:")) return null
        val suffix = token.substringAfter("THIS:", missingDelimiterValue = "").trim()
        if (suffix.isBlank()) return null
        val owner = suffix.substringBefore('#', missingDelimiterValue = "").trim()
        return owner.takeIf { it.isNotBlank() }
    }

    private val injectableFieldsByOwnerClass: Map<String, List<Pair<String, List<InjectionSpec>>>> =
        injectableFields.entries
            .groupBy { ownerClassFromFieldKey(it.key) ?: "" }
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, entries) -> entries.map { it.key to it.value } }

    private val injectableParamsByMethodId: Map<String, List<Pair<String, List<InjectionSpec>>>> =
        injectableParamsByKey.entries
            .groupBy { it.key.substringBefore(':', missingDelimiterValue = "").trim() }
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, entries) -> entries.map { it.key to it.value } }

    private fun isBeanCandidateForTarget(beanTypeFqn: String, targetTypeFqn: String): Boolean {
        if (beanTypeFqn == targetTypeFqn) return true
        if (isAssignableMay(beanTypeFqn, targetTypeFqn)) return true
        if (beanTypeFqn !in knownTypeChildren) return true
        return false
    }

    private fun expandInjectEdgesForward(node: ValueFlowNode): List<ValueFlowEdge> {
        if (frameworkModel == null) return emptyList()

        val token = node.token
        val out = mutableListOf<ValueFlowEdge>()

        if (token.startsWith(BEAN_TOKEN_PREFIX)) {
            val beanType = token.substringAfter(BEAN_TOKEN_PREFIX).trim().takeIf { it.isNotBlank() } ?: return emptyList()

            injectableFieldsByOwnerClass[node.method.classFqn].orEmpty().forEach { (fieldKey, specs) ->
                for (spec in specs) {
                    if (!isBeanCandidateForTarget(beanType, spec.targetTypeFqn)) continue
                    out.add(
                        ValueFlowEdge(
                            kind = ValueFlowEdgeKind.INJECT,
                            from = node,
                            to = ValueFlowNode(method = node.method, token = fieldKey),
                            offset = spec.startOffset,
                            filePath = spec.filePath,
                            details = "inject ${spec.targetTypeFqn} <= $beanType",
                        )
                    )
                }
            }

            injectableParamsByMethodId[node.method.id].orEmpty().forEach { (key, specs) ->
                val suffix = key.substringAfter("${node.method.id}:", missingDelimiterValue = "").trim()
                if (suffix.isBlank()) return@forEach
                val targetToken = "PARAM:$suffix"
                for (spec in specs) {
                    if (!isBeanCandidateForTarget(beanType, spec.targetTypeFqn)) continue
                    out.add(
                        ValueFlowEdge(
                            kind = ValueFlowEdgeKind.INJECT,
                            from = node,
                            to = ValueFlowNode(method = node.method, token = targetToken),
                            offset = spec.startOffset,
                            filePath = spec.filePath,
                            details = "inject ${spec.targetTypeFqn} <= $beanType",
                        )
                    )
                }
            }
        }

        if (token.startsWith(INJECT_TOKEN_PREFIX)) {
            val targetType = token.substringAfter(INJECT_TOKEN_PREFIX).trim().takeIf { it.isNotBlank() } ?: return emptyList()

            injectableFieldsByOwnerClass[node.method.classFqn].orEmpty().forEach { (fieldKey, specs) ->
                for (spec in specs) {
                    if (spec.targetTypeFqn != targetType) continue
                    out.add(
                        ValueFlowEdge(
                            kind = ValueFlowEdgeKind.INJECT,
                            from = node,
                            to = ValueFlowNode(method = node.method, token = fieldKey),
                            offset = spec.startOffset,
                            filePath = spec.filePath,
                            details = "inject $targetType",
                        )
                    )
                }
            }

            injectableParamsByMethodId[node.method.id].orEmpty().forEach { (key, specs) ->
                val suffix = key.substringAfter("${node.method.id}:", missingDelimiterValue = "").trim()
                if (suffix.isBlank()) return@forEach
                val targetToken = "PARAM:$suffix"
                for (spec in specs) {
                    if (spec.targetTypeFqn != targetType) continue
                    out.add(
                        ValueFlowEdge(
                            kind = ValueFlowEdgeKind.INJECT,
                            from = node,
                            to = ValueFlowNode(method = node.method, token = targetToken),
                            offset = spec.startOffset,
                            filePath = spec.filePath,
                            details = "inject $targetType",
                        )
                    )
                }
            }
        }

        return out
    }

    private fun findForwardPath(
        start: ValueFlowNode,
        target: ValueFlowNode,
        maxDepth: Int,
        maxStates: Int,
        maxHeapReadersPerStep: Int,
    ): Map<ValueFlowNode, Parent>? {
        if (start == target) return emptyMap()
        if (maxDepth <= 0 || maxStates <= 0) return null

        val queue = ArrayDeque<TraceState>()
        val visited = linkedSetOf<ValueFlowNode>()
        val parents = hashMapOf<ValueFlowNode, Parent>()
        val safeReaders = maxHeapReadersPerStep.coerceIn(1, 200)

        fun enqueue(next: ValueFlowNode, depth: Int, contextOffset: Int?, via: ValueFlowEdge) {
            if (next.token.isBlank()) return
            if (visited.size >= maxStates) return
            if (!visited.add(next)) return
            parents[next] = Parent(prev = via.from, via = via)
            queue.addLast(TraceState(node = next, depth = depth, contextOffset = contextOffset))
        }

        visited.add(start)
        queue.addLast(TraceState(node = start, depth = 0, contextOffset = null))

        while (queue.isNotEmpty()) {
            val (current, depth, currentOffset) = queue.removeFirst()

            if (current == target) return parents

            if (depth >= maxDepth) continue
            val summary = summaries.summaries[current.method]

            if (summary != null) {
                expandCopiesForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandLoadsForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandStoresForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandCallFromCaller(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandCallInputsApproxToResultsForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandAllocForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
            }

            expandInjectEdgesForward(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            expandReturnToCallerForward(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            expandHeapLoadsForward(current, safeReaders).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
        }

        return null
    }

    private fun traceFromEntryPoints(
        sink: ValueFlowNode,
        maxDepth: Int,
        maxStates: Int,
        maxHeapReadersPerStep: Int,
        maxEntryPointSeeds: Int = 200,
    ): ValueFlowTrace? {
        if (graph.entryPoints.isEmpty()) return null
        if (maxDepth <= 0 || maxStates <= 0) return null

        val safeSeeds = maxEntryPointSeeds.coerceIn(1, 2_000)
        val safeReaders = maxHeapReadersPerStep.coerceIn(1, 200)

        fun candidateEntryPoints(target: MethodRef, maxVisitedMethods: Int = 20_000): List<MethodRef> {
            val visited = hashSetOf<MethodRef>()
            val queue = ArrayDeque<MethodRef>()
            val found = linkedSetOf<MethodRef>()
            visited.add(target)
            queue.addLast(target)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (current in graph.entryPoints) {
                    found.add(current)
                }
                if (visited.size >= maxVisitedMethods) break
                for (caller in graph.incoming[current].orEmpty()) {
                    if (visited.add(caller)) queue.addLast(caller)
                }
            }
            return if (found.isNotEmpty()) found.toList() else graph.entryPoints.toList()
        }

        val seeds =
            buildList<ValueFlowNode> {
                for (ep in candidateEntryPoints(sink.method)) {
                    val count = ep.paramCount.coerceIn(0, 50)
                    for (idx in 0 until count) {
                        add(ValueFlowNode(method = ep, token = "PARAM:$idx"))
                        if (size >= safeSeeds) return@buildList
                    }
                }
            }

        if (seeds.isEmpty()) return null

        fun invert(edge: ValueFlowEdge): ValueFlowEdge =
            ValueFlowEdge(
                kind = edge.kind,
                from = edge.to,
                to = edge.from,
                offset = edge.offset,
                filePath = edge.filePath,
                details = edge.details,
            )

        val queue = ArrayDeque<TraceState>()
        val visited = linkedSetOf<ValueFlowNode>()
        val parents = hashMapOf<ValueFlowNode, Parent>()

        fun enqueue(next: ValueFlowNode, depth: Int, contextOffset: Int?, via: ValueFlowEdge) {
            if (next.token.isBlank()) return
            if (visited.size >= maxStates) return
            if (!visited.add(next)) return
            parents[next] = Parent(prev = via.from, via = via)
            queue.addLast(TraceState(node = next, depth = depth, contextOffset = contextOffset))
        }

        for (seed in seeds) {
            if (visited.size >= maxStates) break
            if (visited.add(seed)) {
                queue.addLast(TraceState(node = seed, depth = 0, contextOffset = null))
            }
        }

        while (queue.isNotEmpty()) {
            val (current, depth, currentOffset) = queue.removeFirst()
            if (current == sink) {
                val (root, forwardEdges) = reconstructToAnyStart(end = sink, parents = parents)
                val backwardEdges = forwardEdges.asReversed().map(::invert)
                return ValueFlowTrace(start = sink, end = root, edges = backwardEdges)
            }

            if (depth >= maxDepth) continue
            val summary = summaries.summaries[current.method]
            if (summary != null) {
                expandCopiesForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandLoadsForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandStoresForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandCallFromCaller(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandCallInputsApproxToResultsForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
                expandAllocForward(current, summary, currentOffset).forEach { edge ->
                    enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
                }
            }

            expandInjectEdgesForward(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            expandReturnToCallerForward(current).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
            expandHeapLoadsForward(current, safeReaders).forEach { edge ->
                enqueue(edge.to, depth + 1, edge.offset ?: currentOffset, edge)
            }
        }

        return null
    }

    private fun reconstructToAnyStart(
        end: ValueFlowNode,
        parents: Map<ValueFlowNode, Parent>,
    ): Pair<ValueFlowNode, List<ValueFlowEdge>> {
        val reversed = mutableListOf<ValueFlowEdge>()
        var cur = end
        while (true) {
            val parent = parents[cur] ?: break
            reversed.add(parent.via)
            cur = parent.prev
        }
        return cur to reversed.reversed()
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
