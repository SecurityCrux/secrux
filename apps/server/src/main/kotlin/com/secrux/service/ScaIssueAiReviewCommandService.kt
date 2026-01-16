package com.secrux.service

import com.secrux.ai.AiClient
import com.secrux.ai.AiJobType
import com.secrux.ai.AiReviewRequest
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.ScaIssue
import com.secrux.dto.AiJobTicketResponse
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.repo.AiClientConfigRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ScaIssueAiReviewCommandService(
    private val aiClient: AiClient,
    private val aiClientConfigRepository: AiClientConfigRepository,
    private val scaIssueReviewService: ScaIssueReviewService,
    private val scaIssueUsageService: ScaIssueUsageService
) {

    fun triggerAiReview(
        tenantId: UUID,
        issue: ScaIssue,
        request: AiReviewTriggerRequest? = null
    ): AiJobTicketResponse {
        val aiClientConfig = aiClientConfigRepository.findDefaultEnabled(tenantId)
        val usageEvidence = scaIssueUsageService.loadEvidenceOrNull(tenantId, issue, maxEntries = 80)
        val payload =
            buildMap<String, Any?> {
                put(
                    "scaIssue",
                    buildMap<String, Any?> {
                        put("issueId", issue.issueId)
                        put("taskId", issue.taskId)
                        put("projectId", issue.projectId)
                        put("sourceEngine", issue.sourceEngine)
                        put("vulnId", issue.vulnId)
                        put("severity", issue.severity.name)
                        put("status", issue.status.name)
                        put("packageName", issue.packageName)
                        put("installedVersion", issue.installedVersion)
                        put("fixedVersion", issue.fixedVersion)
                        put("primaryUrl", issue.primaryUrl)
                        put("componentPurl", issue.componentPurl)
                        put("componentName", issue.componentName)
                        put("componentVersion", issue.componentVersion)
                        put("introducedBy", issue.introducedBy)
                        put("location", issue.location)
                        put("evidence", issue.evidence)
                        put(
                            "usageEvidence",
                            usageEvidence?.let {
                                mapOf(
                                    "generatedAt" to it.generatedAt,
                                    "scannedFiles" to it.scannedFiles,
                                    "entries" to it.entries.map(::truncateUsageEntry)
                                )
                            }
                        )
                    }
                )
                request?.context?.takeIf { it.isNotBlank() }?.let { put("context", it) }
                request?.mode?.takeIf { it.isNotBlank() }?.let { put("mode", it) }
                aiClientConfig?.let {
                    put(
                        "aiClient",
                        mapOf(
                            "provider" to it.provider,
                            "baseUrl" to it.baseUrl,
                            "model" to it.model,
                            "apiKey" to it.apiKey
                        )
                    )
                }
            }

        val ticket =
            runCatching {
                aiClient.review(
                    AiReviewRequest(
                        tenantId = tenantId.toString(),
                        jobType = AiJobType.SCA_ISSUE_REVIEW,
                        targetId = issue.issueId.toString(),
                        agent = request?.agent,
                        payload = payload,
                        context = mapOf(
                            "severity" to issue.severity.name,
                            "status" to issue.status.name,
                            "mode" to request?.mode
                        )
                    )
                )
            }.getOrElse { ex ->
                throw SecruxException(ErrorCode.AI_SERVICE_UNAVAILABLE, ex.message ?: "AI service unavailable")
            }

        scaIssueReviewService.recordAiReviewQueued(tenantId, issue, ticket.jobId)
        return ticket.toResponse()
    }
}

private fun truncateUsageEntry(entry: com.secrux.dto.ScaUsageEntryDto): com.secrux.dto.ScaUsageEntryDto {
    val snippet = entry.snippet?.takeIf { it.isNotBlank() }?.let { it.take(400) }
    return if (snippet == entry.snippet) entry else entry.copy(snippet = snippet)
}

