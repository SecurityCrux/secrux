package com.secrux.service.enrichment

import com.secrux.domain.Finding
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto

interface CodeEnricher {
    fun supports(path: String): Boolean

    fun enrich(
        finding: Finding,
        snippet: CodeSnippetDto?,
        dataFlowNodes: List<DataFlowNodeDto>,
        dataFlowEdges: List<DataFlowEdgeDto>,
    ): Map<String, Any?>?
}

