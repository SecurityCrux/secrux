package com.securitycrux.secrux.intellij.valueflow

import com.securitycrux.secrux.intellij.callgraph.MethodRef

enum class PointsToIndexMode {
    OFF,
    PRECOMPUTE,
}

enum class PointsToAbstraction {
    TYPE,
    ALLOC_SITE,
}

data class PointsToOptions(
    val mode: PointsToIndexMode,
    val abstraction: PointsToAbstraction,
    val maxRootsPerToken: Int,
    val maxBeanCandidates: Int,
    val maxCallTargets: Int,
)

data class PointsToEntry(
    val rootIds: IntArray,
    val truncated: Boolean,
)

data class PointsToStats(
    val entries: Int,
    val methods: Int,
    val rootDictSize: Int,
    val edges: Int,
    val buildMillis: Long,
    val truncatedEntries: Int,
)

data class PointsToResult(
    val roots: Set<String>,
    val truncated: Boolean,
)

data class PointsToIndex(
    val byMethod: Map<MethodRef, Map<String, PointsToEntry>>,
    val rootDict: List<String>,
    val options: PointsToOptions,
    val stats: PointsToStats,
) {
    fun lookup(method: MethodRef, token: String): PointsToEntry? =
        byMethod[method]?.get(token.trim())

    fun query(method: MethodRef, token: String): PointsToResult? {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return null
        val entry = lookup(method = method, token = trimmed) ?: return null
        val roots = linkedSetOf<String>()
        for (id in entry.rootIds) {
            val root = rootDict.getOrNull(id) ?: continue
            roots.add(root)
        }
        return PointsToResult(roots = roots, truncated = entry.truncated)
    }

    companion object {
        const val ROOT_UNKNOWN = "UNKNOWN"
        const val ROOT_UNKNOWN_ID = 0
    }
}

