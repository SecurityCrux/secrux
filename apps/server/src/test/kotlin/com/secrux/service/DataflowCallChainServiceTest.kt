package com.secrux.service

import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DataflowCallChainServiceTest {

    private val service = DataflowCallChainService()

    @Test
    fun `buildCallChains returns multiple disconnected chains`() {
        val nodes =
            listOf(
                DataFlowNodeDto(id = "n0", label = "entry", role = "SOURCE", file = "A.java", line = 1),
                DataFlowNodeDto(id = "n1", label = "sink1", role = "SINK", file = "A.java", line = 2),
                DataFlowNodeDto(id = "n2", label = "entry2", role = "SOURCE", file = "B.java", line = 10),
                DataFlowNodeDto(id = "n3", label = "call", role = "PROPAGATOR", file = "B.java", line = 11),
                DataFlowNodeDto(id = "n4", label = "sink2", role = "SINK", file = "B.java", line = 12),
            )
        val edges =
            listOf(
                DataFlowEdgeDto(source = "n0", target = "n1", label = "dataflow"),
                DataFlowEdgeDto(source = "n2", target = "n3", label = "dataflow"),
                DataFlowEdgeDto(source = "n3", target = "n4", label = "dataflow"),
            )

        val chains = service.buildCallChains(nodes = nodes, edges = edges)
        assertEquals(2, chains.size)
        assertEquals(listOf("n0", "n1"), chains[0].steps.map { it.nodeId })
        assertEquals(listOf("n2", "n3", "n4"), chains[1].steps.map { it.nodeId })
    }

    @Test
    fun `buildCallChains splits branching paths`() {
        val nodes =
            listOf(
                DataFlowNodeDto(id = "n0", label = "entry", role = "SOURCE", file = "A.java", line = 1),
                DataFlowNodeDto(id = "n1", label = "p1", role = "PROPAGATOR", file = "A.java", line = 2),
                DataFlowNodeDto(id = "n2", label = "p2", role = "PROPAGATOR", file = "A.java", line = 3),
                DataFlowNodeDto(id = "n3", label = "sink", role = "SINK", file = "A.java", line = 4),
            )
        val edges =
            listOf(
                DataFlowEdgeDto(source = "n0", target = "n1", label = "dataflow"),
                DataFlowEdgeDto(source = "n0", target = "n2", label = "dataflow"),
                DataFlowEdgeDto(source = "n1", target = "n3", label = "dataflow"),
                DataFlowEdgeDto(source = "n2", target = "n3", label = "dataflow"),
            )

        val chains = service.buildCallChains(nodes = nodes, edges = edges)
        assertEquals(2, chains.size)
        val paths = chains.map { it.steps.map { step -> step.nodeId } }.toSet()
        assertEquals(setOf(listOf("n0", "n1", "n3"), listOf("n0", "n2", "n3")), paths)
    }
}

