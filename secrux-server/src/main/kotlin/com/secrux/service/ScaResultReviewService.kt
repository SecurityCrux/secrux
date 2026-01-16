package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.FindingStatus
import com.secrux.domain.ScaAiReviewSpec
import com.secrux.domain.Severity
import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.TaskType
import com.secrux.dto.ResultReviewRequest
import com.secrux.dto.StageSummary
import com.secrux.repo.AiClientConfigRepository
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.TaskRepository
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ScaResultReviewService(
    private val taskRepository: TaskRepository,
    private val scaIssueRepository: ScaIssueRepository,
    private val scaIssueAiReviewCommandService: ScaIssueAiReviewCommandService,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val aiClientConfigRepository: AiClientConfigRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(ScaResultReviewService::class.java)

    fun run(
        tenantId: UUID,
        taskId: UUID,
        stageId: UUID,
        request: ResultReviewRequest? = null,
    ): StageSummary {
        val task =
            taskRepository.findById(taskId, tenantId)
                ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found for SCA result review")
        if (task.type != TaskType.SCA_CHECK) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Task ${task.taskId} is not an SCA task")
        }

        return LogContext.with(
            LogContext.TENANT_ID to tenantId,
            LogContext.TASK_ID to taskId,
            LogContext.CORRELATION_ID to task.correlationId,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to StageType.RESULT_REVIEW.name,
        ) {
            val startedAt = OffsetDateTime.now(clock)
            val aiRequested = request?.aiReviewEnabled ?: task.spec.scaAiReview.enabled
            val enabledSeverities = enabledSeverities(task.spec.scaAiReview)

            if (!aiRequested) {
                val skipReason =
                    if (request?.aiReviewEnabled == false) {
                        "disabled_by_request"
                    } else {
                        "disabled_by_task_spec"
                    }
                return@with persistSkipped(taskId, tenantId, task.correlationId, stageId, startedAt, skipReason, enabledSeverities)
            }

            if (enabledSeverities.isEmpty()) {
                return@with persistSkipped(taskId, tenantId, task.correlationId, stageId, startedAt, "no_severities_enabled", enabledSeverities)
            }

            val aiClientEnabled = aiClientConfigRepository.findDefaultEnabled(tenantId) != null
            if (!aiClientEnabled) {
                return@with persistSkipped(taskId, tenantId, task.correlationId, stageId, startedAt, "ai_client_disabled", enabledSeverities)
            }

            val issues = scaIssueRepository.listByTask(tenantId, taskId)
            val openIssues = issues.filter { it.status == FindingStatus.OPEN }
            val queuedIssues =
                openIssues.filter { isSeverityEnabled(task.spec.scaAiReview, it.severity) }

            val queuedBySeverity =
                queuedIssues
                    .groupingBy { it.severity.name }
                    .eachCount()

            queuedIssues.forEach { issue ->
                scaIssueAiReviewCommandService.triggerAiReview(tenantId, issue)
            }

            val endedAt = OffsetDateTime.now(clock)
            val stage =
                Stage(
                    stageId = stageId,
                    tenantId = tenantId,
                    taskId = taskId,
                    type = StageType.RESULT_REVIEW,
                    spec =
                        StageSpec(
                            version = "v1",
                            params =
                                mapOf(
                                    "aiReviewEnabled" to true,
                                    "openIssues" to openIssues.size,
                                    "aiReviewQueued" to queuedIssues.size,
                                    "enabledSeverities" to enabledSeverities,
                                    "queuedBySeverity" to queuedBySeverity,
                                ),
                        ),
                    status = StageStatus.SUCCEEDED,
                    metrics = StageMetrics(durationMs = Duration.between(startedAt, endedAt).toMillis()),
                    signals = StageSignals(needsAiReview = false),
                    artifacts = listOf("sca:review:open=${openIssues.size}:queued=${queuedIssues.size}"),
                    startedAt = startedAt,
                    endedAt = endedAt,
                )
            stageLifecycle.persist(stage, task.correlationId)
            taskLogService.logStageEvent(
                taskId = taskId,
                stageId = stageId,
                stageType = StageType.RESULT_REVIEW,
                message = "SCA open=${openIssues.size}, aiQueued=${queuedIssues.size} (enabledSeverities=${enabledSeverities.joinToString(",")})",
            )
            log.info(
                "event=sca_result_reviewed openIssues={} queuedIssues={} enabledSeverities={}",
                openIssues.size,
                queuedIssues.size,
                enabledSeverities.joinToString(","),
            )
            stage.toSummary()
        }
    }

    private fun persistSkipped(
        taskId: UUID,
        tenantId: UUID,
        correlationId: String,
        stageId: UUID,
        startedAt: OffsetDateTime,
        reason: String,
        enabledSeverities: List<String>,
    ): StageSummary {
        val endedAt = startedAt
        val stage =
            Stage(
                stageId = stageId,
                tenantId = tenantId,
                taskId = taskId,
                type = StageType.RESULT_REVIEW,
                spec =
                    StageSpec(
                        version = "v1",
                        params =
                            mapOf(
                                "aiReviewEnabled" to false,
                                "reason" to reason,
                                "enabledSeverities" to enabledSeverities,
                            ),
                    ),
                status = StageStatus.SKIPPED,
                metrics = StageMetrics(durationMs = 0),
                signals = StageSignals(needsAiReview = false),
                artifacts = listOf("sca:review:skipped:$reason"),
                startedAt = startedAt,
                endedAt = endedAt,
            )
        stageLifecycle.persist(stage, correlationId)
        taskLogService.logStageEvent(
            taskId = taskId,
            stageId = stageId,
            stageType = StageType.RESULT_REVIEW,
            message = "SCA result review skipped (reason=$reason)",
        )
        log.info("event=sca_result_review_skipped reason={}", reason)
        return stage.toSummary()
    }
}

private fun enabledSeverities(spec: ScaAiReviewSpec): List<String> =
    buildList {
        if (spec.critical) add(Severity.CRITICAL.name)
        if (spec.high) add(Severity.HIGH.name)
        if (spec.medium) add(Severity.MEDIUM.name)
        if (spec.low) add(Severity.LOW.name)
        if (spec.info) add(Severity.INFO.name)
    }

private fun isSeverityEnabled(spec: ScaAiReviewSpec, severity: Severity): Boolean =
    when (severity) {
        Severity.CRITICAL -> spec.critical
        Severity.HIGH -> spec.high
        Severity.MEDIUM -> spec.medium
        Severity.LOW -> spec.low
        Severity.INFO -> spec.info
    }

