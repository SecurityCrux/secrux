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

data class CallGraph(
    val methods: Map<MethodRef, MethodLocation>,
    val outgoing: Map<MethodRef, Set<MethodRef>>,
    val incoming: Map<MethodRef, Set<MethodRef>>,
    val entryPoints: Set<MethodRef> = emptySet(),
    val stats: CallGraphStats
)

data class CallGraphStats(
    val filesScanned: Int,
    val methodsIndexed: Int,
    val callEdges: Int,
    val unresolvedCalls: Int
)
