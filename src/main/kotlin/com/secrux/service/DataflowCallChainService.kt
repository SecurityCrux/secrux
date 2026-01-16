package com.secrux.service

import com.secrux.dto.CallChainDto
import com.secrux.dto.CallChainStepDto
import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto
import org.springframework.stereotype.Service

@Service
class DataflowCallChainService {

    fun buildCallChains(
        nodes: List<DataFlowNodeDto>,
        edges: List<DataFlowEdgeDto>,
        maxChains: Int = 20,
        maxDepth: Int = 200,
    ): List<CallChainDto> {
        if (nodes.isEmpty()) return emptyList()
        val safeMaxChains = maxChains.coerceIn(1, 200)
        val safeMaxDepth = maxDepth.coerceIn(1, 2000)

        val nodeById = nodes.associateBy { it.id }
        val outgoing = nodes.associate { it.id to mutableListOf<String>() }
        val indegree = nodes.associate { it.id to 0 }.toMutableMap()

        for (edge in edges) {
            val source = edge.source
            val target = edge.target
            if (!nodeById.containsKey(source) || !nodeById.containsKey(target)) continue
            outgoing[source]?.add(target)
            indegree[target] = (indegree[target] ?: 0) + 1
        }

        val startIds =
            nodes
                .asSequence()
                .map { it.id }
                .filter { (indegree[it] ?: 0) == 0 }
                .toList()
                .ifEmpty { nodes.map { it.id } }

        val paths = mutableListOf<List<String>>()
        val seen = LinkedHashSet<String>()

        fun record(path: List<String>) {
            if (path.isEmpty()) return
            val key = path.joinToString("->")
            if (seen.add(key)) {
                paths.add(path.toList())
            }
        }

        fun isTerminal(id: String): Boolean {
            val node = nodeById[id]
            if (node?.role?.equals("SINK", ignoreCase = true) == true) return true
            return outgoing[id].isNullOrEmpty()
        }

        fun dfs(current: String, path: MutableList<String>) {
            if (paths.size >= safeMaxChains) return
            if (path.size >= safeMaxDepth || isTerminal(current)) {
                record(path)
                return
            }
            val nexts = outgoing[current].orEmpty()
            if (nexts.isEmpty()) {
                record(path)
                return
            }
            for (next in nexts) {
                if (next in path) continue
                path.add(next)
                dfs(next, path)
                path.removeAt(path.lastIndex)
                if (paths.size >= safeMaxChains) return
            }
        }

        for (start in startIds) {
            dfs(start, mutableListOf(start))
            if (paths.size >= safeMaxChains) break
        }

        if (paths.isEmpty()) {
            record(nodes.map { it.id })
        }

        return paths.take(safeMaxChains).mapIndexed { index, ids ->
            CallChainDto(
                chainId = "chain${index + 1}",
                steps =
                    ids.mapNotNull { id ->
                        nodeById[id]?.let { node ->
                            CallChainStepDto(
                                nodeId = node.id,
                                role = node.role,
                                label = node.label,
                                file = node.file,
                                line = node.line,
                                startColumn = node.startColumn,
                                endColumn = node.endColumn,
                                snippet = node.value,
                            )
                        }
                    },
            )
        }.filter { it.steps.isNotEmpty() }
    }
}

