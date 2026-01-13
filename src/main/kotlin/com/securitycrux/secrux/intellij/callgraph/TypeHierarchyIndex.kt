package com.securitycrux.secrux.intellij.callgraph

enum class TypeEdgeKind {
    IMPL,
    EXTE,
}

data class TypeHierarchyEdge(
    val from: String,
    val to: String,
    val kind: TypeEdgeKind,
)

data class TypeHierarchyStats(
    val typesIndexed: Int,
    val edges: Int,
    val implEdges: Int,
    val exteEdges: Int,
)

data class TypeHierarchyIndex(
    val edges: Set<TypeHierarchyEdge>,
    val stats: TypeHierarchyStats,
) {
    fun childrenOf(parentFqn: String, kind: TypeEdgeKind? = null): Set<String> {
        if (parentFqn.isBlank()) return emptySet()
        return edges
            .asSequence()
            .filter { edge -> edge.to == parentFqn && (kind == null || edge.kind == kind) }
            .map { edge -> edge.from }
            .toSet()
    }

    fun parentsOf(childFqn: String, kind: TypeEdgeKind? = null): Set<String> {
        if (childFqn.isBlank()) return emptySet()
        return edges
            .asSequence()
            .filter { edge -> edge.from == childFqn && (kind == null || edge.kind == kind) }
            .map { edge -> edge.to }
            .toSet()
    }
}

