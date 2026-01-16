package com.securitycrux.secrux.intellij.callgraph

import com.intellij.openapi.vfs.VirtualFile

data class MethodRef(
    val classFqn: String,
    val name: String,
    val paramCount: Int
) {
    val id: String
        get() = "$classFqn#$name/$paramCount"

    companion object {
        fun fromIdOrNull(id: String): MethodRef? {
            val hashIdx = id.lastIndexOf('#')
            val slashIdx = id.lastIndexOf('/')
            if (hashIdx <= 0 || slashIdx <= hashIdx) return null
            val classFqn = id.substring(0, hashIdx)
            val name = id.substring(hashIdx + 1, slashIdx)
            val paramCount = id.substring(slashIdx + 1).toIntOrNull() ?: return null
            return MethodRef(classFqn = classFqn, name = name, paramCount = paramCount)
        }
    }
}

data class MethodLocation(
    val file: VirtualFile,
    val startOffset: Int
)

data class CallEdge(
    val caller: MethodRef,
    val callee: MethodRef
)

enum class CallEdgeKind {
    CALL,
    IMPL,
    EXTE,
}

data class CallSiteLocation(
    val file: VirtualFile,
    val startOffset: Int
)

data class CallGraph(
    val methods: Map<MethodRef, MethodLocation>,
    val outgoing: Map<MethodRef, Set<MethodRef>>,
    val incoming: Map<MethodRef, Set<MethodRef>>,
    val callSites: Map<CallEdge, List<CallSiteLocation>> = emptyMap(),
    val edgeKinds: Map<CallEdge, CallEdgeKind> = emptyMap(),
    val entryPoints: Set<MethodRef> = emptySet(),
    val stats: CallGraphStats
)

data class CallGraphStats(
    val filesScanned: Int,
    val methodsIndexed: Int,
    val callEdges: Int,
    val unresolvedCalls: Int
)

fun CallGraph.callSiteFor(edge: CallEdge, preferredOffset: Int? = null): CallSiteLocation? {
    val sites = callSites[edge].orEmpty()
    if (sites.isEmpty()) return null
    if (preferredOffset != null) {
        sites.firstOrNull { it.startOffset == preferredOffset }?.let { return it }
    }
    return sites.firstOrNull()
}

fun CallGraph.callSiteOffsets(edge: CallEdge): Set<Int> =
    callSites[edge]?.mapTo(linkedSetOf()) { it.startOffset } ?: emptySet()
