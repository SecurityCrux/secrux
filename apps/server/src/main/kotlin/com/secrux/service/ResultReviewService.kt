package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.dto.ResultReviewRequest
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.dto.StageSummary
import com.secrux.dto.TicketCreationRequest
import com.secrux.dto.TicketPolicyRequest
import com.secrux.repo.AiClientConfigRepository
import com.secrux.repo.FindingRepository
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ResultReviewService(
    private val taskRepository: TaskRepository,
    private val findingRepository: FindingRepository,
    private val findingService: FindingService,
    private val stageLifecycle: StageLifecycle,
    private val ticketService: TicketService,
    private val taskLogService: TaskLogService,
    private val aiClientConfigRepository: AiClientConfigRepository,
    private val clock: Clock
) {

    fun run(tenantId: UUID, taskId: UUID, stageId: UUID, request: ResultReviewRequest): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for result review")
        val aiRequested = request.aiReviewEnabled ?: task.spec.aiReview.enabled
        val defaultModeRaw = request.aiReviewMode ?: task.spec.aiReview.mode
        val defaultMode = defaultModeRaw.trim().lowercase()
        val dataFlowModeRaw = request.aiReviewDataFlowMode ?: task.spec.aiReview.dataFlowMode
        val dataFlowMode = dataFlowModeRaw?.trim()?.lowercase()

        if (request.aiReviewEnabled == true && request.aiReviewMode.isNullOrBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "aiReviewMode is required when aiReviewEnabled=true")
        }

        if (!aiRequested) {
            val skipReason =
                if (request.aiReviewEnabled == false) {
                    "disabled_by_request"
                } else {
                    "disabled_by_task_spec"
                }
            val startedAt = OffsetDateTime.now(clock)
            val endedAt = startedAt
            val stage = Stage(
                stageId = stageId,
                tenantId = tenantId,
                taskId = taskId,
                type = StageType.RESULT_REVIEW,
                spec = StageSpec(
                    version = "v1",
                    params = mapOf(
                        "aiReviewEnabled" to false,
                        "reason" to skipReason
                    )
                ),
                status = StageStatus.SKIPPED,
                metrics = StageMetrics(durationMs = 0),
                signals = StageSignals(needsAiReview = false, autoFixPossible = false),
                artifacts = listOf("review:skipped"),
                startedAt = startedAt,
                endedAt = endedAt
            )
            stageLifecycle.persist(stage, task.correlationId)
            taskLogService.logStageEvent(
                taskId = taskId,
                stageId = stageId,
                stageType = StageType.RESULT_REVIEW,
                message = "Result review skipped (AI review disabled)"
            )
            return stage.toSummary()
        }

        if (defaultMode !in setOf("simple", "precise")) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "aiReviewMode must be one of: simple, precise")
        }
        if (!dataFlowMode.isNullOrBlank() && dataFlowMode !in setOf("simple", "precise")) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "aiReviewDataFlowMode must be one of: simple, precise")
        }

        val findings = findingRepository.listByTask(tenantId, taskId)
        val openFindings = findings.filter { it.status == FindingStatus.OPEN }
        val aiClientEnabled = aiClientConfigRepository.findDefaultEnabled(tenantId) != null
        if (!aiClientEnabled) {
            val startedAt = OffsetDateTime.now(clock)
            val endedAt = startedAt
            val stage = Stage(
                stageId = stageId,
                tenantId = tenantId,
                taskId = taskId,
                type = StageType.RESULT_REVIEW,
                spec = StageSpec(
                    version = "v1",
                    params = mapOf(
                        "aiReviewEnabled" to true,
                        "aiClientEnabled" to false,
                        "aiReviewMode" to defaultMode,
                        "aiReviewDataFlowMode" to (dataFlowMode ?: defaultMode),
                        "openFindings" to openFindings.size
                    )
                ),
                status = StageStatus.SKIPPED,
                metrics = StageMetrics(durationMs = 0),
                signals = StageSignals(needsAiReview = false, autoFixPossible = false),
                artifacts = listOf("review:skipped:aiClientDisabled"),
                startedAt = startedAt,
                endedAt = endedAt
            )
            stageLifecycle.persist(stage, task.correlationId)
            taskLogService.logStageEvent(
                taskId = taskId,
                stageId = stageId,
                stageType = StageType.RESULT_REVIEW,
                message = "Result review skipped (AI client not configured)"
            )
            return stage.toSummary()
        }

        val queuedForAiReview = openFindings

        // Auto-ticket only high-severity OPEN findings. Do not auto-mark remaining findings as FALSE_POSITIVE.
        val ticketCandidates = openFindings.filter { severityRank(it.severity) >= severityRank(Severity.HIGH) }
        if (request.autoTicket && ticketCandidates.isNotEmpty()) {
            val ticketRequest = TicketCreationRequest(
                projectId = task.projectId,
                provider = request.ticketProvider,
                findingIds = ticketCandidates.map { it.findingId },
                ticketPolicy = TicketPolicyRequest(
                    project = "AUTO",
                    assigneeStrategy = "OWNER"
                ),
                summary = "New findings (${ticketCandidates.size})",
                labels = request.labels
            )
            ticketService.createTickets(tenantId, ticketRequest)
        }

        var queuedSimple = 0
        var queuedPrecise = 0
        if (queuedForAiReview.isNotEmpty()) {
            queuedForAiReview.forEach { finding ->
                val modeForFinding =
                    if (hasDataFlow(finding.evidence)) {
                        dataFlowMode ?: "precise"
                    } else {
                        defaultMode
                    }
                findingService.triggerAiReview(
                    tenantId = tenantId,
                    findingId = finding.findingId,
                    request = AiReviewTriggerRequest(mode = modeForFinding)
                )
                when (modeForFinding) {
                    "precise" -> queuedPrecise += 1
                    else -> queuedSimple += 1
                }
            }
        }

        val startedAt = OffsetDateTime.now(clock)
        val endedAt = startedAt
        val stage = Stage(
            stageId = stageId,
            tenantId = tenantId,
            taskId = taskId,
            type = StageType.RESULT_REVIEW,
            spec = StageSpec(
                version = "v1",
                params = mapOf(
                    "openFindings" to openFindings.size,
                    "aiReviewQueued" to queuedForAiReview.size,
                    "aiReviewEnabled" to true,
                    "aiReviewMode" to defaultMode,
                    "aiReviewDataFlowMode" to (dataFlowMode ?: "precise"),
                    "aiReviewQueuedSimple" to queuedSimple,
                    "aiReviewQueuedPrecise" to queuedPrecise,
                    "ticketCandidates" to ticketCandidates.size,
                    "ticketCreated" to (request.autoTicket && ticketCandidates.isNotEmpty())
                )
            ),
            status = StageStatus.SUCCEEDED,
            metrics = StageMetrics(durationMs = Duration.between(startedAt, endedAt).toMillis()),
            signals = StageSignals(needsAiReview = false, autoFixPossible = false),
            artifacts = listOf("review:open=${openFindings.size}:aiQueued=${queuedForAiReview.size}:ticket=${ticketCandidates.size}"),
            startedAt = startedAt,
            endedAt = endedAt
        )
        stageLifecycle.persist(stage, task.correlationId)
        taskLogService.logStageEvent(
            taskId = taskId,
            stageId = stageId,
            stageType = StageType.RESULT_REVIEW,
            message = "Open=${openFindings.size}, aiQueued=${queuedForAiReview.size} (simple=${queuedSimple}, precise=${queuedPrecise}), tickets=${ticketCandidates.size}"
        )
        return stage.toSummary()
    }

    private fun severityRank(severity: Severity): Int =
        when (severity) {
            Severity.CRITICAL -> 4
            Severity.HIGH -> 3
            Severity.MEDIUM -> 2
            Severity.LOW -> 1
            Severity.INFO -> 0
        }

    private fun hasDataFlow(evidence: Map<String, Any?>?): Boolean {
        val df = evidence?.get("dataflow") ?: evidence?.get("dataFlow") ?: return false
        val map = df as? Map<*, *> ?: return false
        val nodes = map["nodes"] as? List<*> ?: emptyList<Any?>()
        val edges = map["edges"] as? List<*> ?: emptyList<Any?>()
        return nodes.isNotEmpty() || edges.isNotEmpty()
    }
}
