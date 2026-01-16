package com.secrux.service.enrichment

import com.secrux.domain.Finding
import com.secrux.dto.CodeSnippetDto
import com.secrux.dto.DataFlowEdgeDto
import com.secrux.dto.DataFlowNodeDto
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AiReviewEnrichmentService(
    private val enrichers: List<CodeEnricher>
) {

    private val log = LoggerFactory.getLogger(AiReviewEnrichmentService::class.java)

    fun enrich(
        finding: Finding,
        snippet: CodeSnippetDto?,
        dataFlowNodes: List<DataFlowNodeDto>,
        dataFlowEdges: List<DataFlowEdgeDto>,
        mode: String?
    ): Map<String, Any?>? {
        val safeMode = mode?.trim()?.lowercase()
        if (safeMode != "precise") {
            return null
        }
        val path = finding.location["path"]?.toString() ?: return null
        val enricher = enrichers.firstOrNull { it.supports(path) } ?: return null
        return runCatching {
            enricher.enrich(
                finding = finding,
                snippet = snippet,
                dataFlowNodes = dataFlowNodes,
                dataFlowEdges = dataFlowEdges
            )
        }.getOrElse { ex ->
            LogContext.with(
                LogContext.TENANT_ID to finding.tenantId,
                LogContext.TASK_ID to finding.taskId
            ) {
                log.warn("event=ai_review_enrichment_failed findingId={} error={}", finding.findingId, ex.message, ex)
            }
            null
        }
    }
}
