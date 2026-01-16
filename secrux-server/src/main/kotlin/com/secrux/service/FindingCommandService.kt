package com.secrux.service

import com.secrux.ai.AiClient
import com.secrux.ai.AiJobTicket
import com.secrux.ai.AiJobType
import com.secrux.ai.AiReviewRequest
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Finding
import com.secrux.dto.AiJobTicketResponse
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.dto.FindingBatchRequest
import com.secrux.dto.FindingBatchStatusUpdateFailure
import com.secrux.dto.FindingBatchStatusUpdateRequest
import com.secrux.dto.FindingBatchStatusUpdateResponse
import com.secrux.dto.FindingStatusUpdateRequest
import com.secrux.dto.FindingSummary
import com.secrux.events.PlatformEvent
import com.secrux.events.PlatformEventPublisher
import com.secrux.repo.AiClientConfigRepository
import com.secrux.repo.FindingRepository
import com.secrux.repo.TaskRepository
import com.secrux.security.SecruxPrincipal
import com.secrux.service.enrichment.AiReviewEnrichmentService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class FindingCommandService(
    private val taskRepository: TaskRepository,
    private val findingRepository: FindingRepository,
    private val eventPublisher: PlatformEventPublisher,
    private val aiClient: AiClient,
    private val aiClientConfigRepository: AiClientConfigRepository,
    private val findingReviewService: FindingReviewService,
    private val aiReviewEnrichmentService: AiReviewEnrichmentService,
    private val evidenceService: FindingEvidenceService,
    private val dataflowCallChainService: DataflowCallChainService,
    private val findingSummaryMapper: FindingSummaryMapper,
    private val clock: Clock
) {

    fun ingestFindings(
        tenantId: UUID,
        taskId: UUID,
        request: FindingBatchRequest
    ): List<FindingSummary> {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        val now = OffsetDateTime.now(clock)
        val findings = request.findings.map { dto ->
            Finding(
                findingId = UUID.randomUUID(),
                tenantId = tenantId,
                taskId = taskId,
                projectId = task.projectId,
                sourceEngine = dto.sourceEngine,
                ruleId = dto.ruleId,
                location = dto.location,
                evidence = dto.evidence,
                severity = dto.severity,
                fingerprint = dto.fingerprint,
                status = dto.status,
                introducedBy = dto.introducedBy,
                fixVersion = dto.fixVersion,
                exploitMaturity = dto.exploitMaturity,
                createdAt = now,
                updatedAt = now
            )
        }
        findingRepository.upsertAll(findings)
        eventPublisher.publish(
            PlatformEvent(
                tenantId = tenantId,
                event = "FindingsReady",
                correlationId = task.correlationId,
                payload = mapOf(
                    "tenant_id" to tenantId.toString(),
                    "task_id" to taskId.toString(),
                    "format" to (request.format ?: "SARIF"),
                    "count" to findings.size,
                    "artifactLocations" to (request.artifactLocations ?: emptyList<String>())
                ),
                createdAt = now
            )
        )
        return findingSummaryMapper.toSummaries(findings)
    }

    fun updateStatus(
        principal: SecruxPrincipal,
        findingId: UUID,
        request: FindingStatusUpdateRequest
    ): FindingSummary {
        val finding = findingRepository.findById(findingId, principal.tenantId)
            ?: throw SecruxException(ErrorCode.FINDING_NOT_FOUND, "Finding not found")
        findingRepository.updateStatus(findingId, principal.tenantId, request.status, request.fixVersion)
        val review =
            findingReviewService.recordHumanReview(
                principal = principal,
                finding = finding,
                statusAfter = request.status,
                reason = request.reason
            )
        val updated = finding.copy(status = request.status, fixVersion = request.fixVersion)
        return findingSummaryMapper.toSummary(updated, review)
    }

    fun updateStatusBatch(
        principal: SecruxPrincipal,
        request: FindingBatchStatusUpdateRequest
    ): FindingBatchStatusUpdateResponse {
        val ids = request.findingIds.distinct()
        val findings = findingRepository.findByIds(principal.tenantId, ids)
        val foundById = findings.associateBy { it.findingId }
        val missing = ids.filterNot { foundById.containsKey(it) }
        val foundIds = findings.map { it.findingId }

        findingRepository.updateStatusBatch(
            tenantId = principal.tenantId,
            findingIds = foundIds,
            status = request.status,
            fixVersion = request.fixVersion
        )

        val updated =
            findings.map { finding ->
                val review =
                    findingReviewService.recordHumanReview(
                        principal = principal,
                        finding = finding,
                        statusAfter = request.status,
                        reason = request.reason
                    )
                findingSummaryMapper.toSummary(finding.copy(status = request.status, fixVersion = request.fixVersion), review)
            }

        val failed =
            missing.map { findingId ->
                FindingBatchStatusUpdateFailure(
                    findingId = findingId,
                    error = "Finding not found"
                )
            }

        return FindingBatchStatusUpdateResponse(
            updated = updated,
            failed = failed
        )
    }

    fun deleteFinding(
        tenantId: UUID,
        findingId: UUID
    ) {
        findingRepository.findById(findingId, tenantId)
            ?: throw SecruxException(ErrorCode.FINDING_NOT_FOUND, "Finding not found")
        findingRepository.softDelete(findingId, tenantId)
    }

    fun triggerAiReview(
        tenantId: UUID,
        findingId: UUID,
        request: AiReviewTriggerRequest? = null
    ): AiJobTicketResponse =
        findingRepository.findById(findingId, tenantId)?.let { finding ->
            val snippet = evidenceService.loadSnippet(finding.location)
                ?: evidenceService.extractSnippetFromEvidence(finding.evidence)
            val (nodesRaw, edges) = evidenceService.parseDataflow(finding.evidence)
            val aiClientConfig = aiClientConfigRepository.findDefaultEnabled(tenantId)
            val platformEnrichment = aiReviewEnrichmentService.enrich(
                finding = finding,
                snippet = snippet,
                dataFlowNodes = nodesRaw,
                dataFlowEdges = edges,
                mode = request?.mode
            )
            val evidenceEnrichment = evidenceService.extractEnrichmentFromEvidence(finding.evidence)
            val enrichment = platformEnrichment ?: evidenceEnrichment
            val nodes = nodesRaw.map { node -> node.copy(file = evidenceService.normalizeWorkspaceNodeFile(finding.taskId, node.file)) }
            val callChainsRaw = dataflowCallChainService.buildCallChains(nodes = nodes, edges = edges)
            val callChains = evidenceService.normalizeCallChainsForDisplay(taskId = finding.taskId, callChains = callChainsRaw)
            val ticket = aiClient.review(
                AiReviewRequest(
                    tenantId = tenantId.toString(),
                    jobType = AiJobType.FINDING_REVIEW,
                    targetId = finding.findingId.toString(),
                    agent = request?.agent,
                    payload =
                        mapOf(
                            "location" to finding.location,
                            "finding" to mapOf(
                                "findingId" to finding.findingId,
                                "taskId" to finding.taskId,
                                "projectId" to finding.projectId,
                                "ruleId" to finding.ruleId,
                                "severity" to finding.severity.name,
                                "location" to finding.location,
                                "codeSnippet" to snippet,
                                "dataflow" to mapOf("nodes" to nodes, "edges" to edges),
                                "callChains" to callChains,
                                "enrichment" to enrichment
                            ),
                            "mode" to request?.mode
                        ) + (aiClientConfig?.let {
                            mapOf(
                                "aiClient" to mapOf(
                                    "provider" to it.provider,
                                    "baseUrl" to it.baseUrl,
                                    "model" to it.model,
                                    "apiKey" to it.apiKey
                                )
                            )
                        } ?: emptyMap()),
                    context = mapOf(
                        "severity" to finding.severity.name,
                        "status" to finding.status.name,
                        "mode" to request?.mode
                    )
                )
            )
            findingReviewService.recordAiReviewQueued(tenantId, finding, ticket.jobId)
            ticket.toResponse()
        } ?: throw SecruxException(ErrorCode.FINDING_NOT_FOUND, "Finding not found")
}
