package com.secrux.service

import com.secrux.ai.AiClient
import com.secrux.ai.AiJobTicket
import com.secrux.ai.AiReviewRequest
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.ResourceLimits
import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.dto.AiJobTicketResponse
import com.secrux.dto.AiReviewTriggerRequest
import com.secrux.dto.StageStatusUpdateRequest
import com.secrux.dto.StageSummary
import com.secrux.dto.StageUpsertRequest
import com.secrux.dto.StageMetricsPayload
import com.secrux.dto.StageSignalsPayload
import com.secrux.dto.ResourceLimitsPayload
import com.secrux.repo.StageRepository
import com.secrux.repo.TaskRepository
import com.secrux.repo.AiClientConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class StageService(
    private val taskRepository: TaskRepository,
    private val stageRepository: StageRepository,
    private val stageLifecycle: StageLifecycle,
    private val aiClient: AiClient,
    private val aiClientConfigRepository: AiClientConfigRepository
) {

    private val log = LoggerFactory.getLogger(StageService::class.java)

    fun upsertStage(tenantId: UUID, taskId: UUID, stageId: UUID, request: StageUpsertRequest): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        val stage = Stage(
            stageId = stageId,
            tenantId = tenantId,
            taskId = taskId,
            type = request.type,
            spec = StageSpec(
                version = request.version,
                inputs = request.inputs ?: emptyMap(),
                params = request.params ?: emptyMap(),
                resources = request.resources.toDomain(),
                env = request.env ?: emptyMap(),
                shards = request.shards ?: emptyList(),
                trace = request.trace ?: emptyMap()
            ),
            status = request.status,
            metrics = request.metrics.toDomain(),
            signals = request.signals.toDomain(),
            artifacts = request.artifacts ?: emptyList(),
            startedAt = request.startedAt.toOffsetDateTime(),
            endedAt = request.endedAt.toOffsetDateTime()
        )
        stageLifecycle.persist(stage, task.correlationId)
        return stage.toSummary()
    }

    fun updateStageStatus(
        tenantId: UUID,
        taskId: UUID,
        stageId: UUID,
        request: StageStatusUpdateRequest
    ): StageSummary {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        val current = stageRepository.findById(stageId, tenantId)
            ?: throw SecruxException(ErrorCode.STAGE_NOT_FOUND, "Stage not found")
        ensureStageBelongs(current, taskId)
        val updated = current.copy(
            status = request.status ?: current.status,
            metrics = request.metrics.toDomain(current.metrics),
            signals = request.signals.toDomain(current.signals),
            artifacts = request.artifacts ?: current.artifacts,
            startedAt = request.startedAt.toOffsetDateTime() ?: current.startedAt,
            endedAt = request.endedAt.toOffsetDateTime() ?: current.endedAt
        )
        stageLifecycle.persist(updated, task.correlationId)
        return updated.toSummary()
    }

    fun getStage(tenantId: UUID, taskId: UUID, stageId: UUID): StageSummary {
        val stage = stageRepository.findById(stageId, tenantId)
            ?: throw SecruxException(ErrorCode.STAGE_NOT_FOUND, "Stage not found")
        ensureStageBelongs(stage, taskId)
        return stage.toSummary()
    }

    fun triggerAiReview(
        tenantId: UUID,
        taskId: UUID,
        stageId: UUID,
        request: AiReviewTriggerRequest
    ): AiJobTicketResponse =
        stageRepository.findById(stageId, tenantId)?.let { stage ->
            ensureStageBelongs(stage, taskId)
            val aiClientConfig = aiClientConfigRepository.findDefaultEnabled(tenantId)
            aiClient.review(
                AiReviewRequest(
                    tenantId = tenantId.toString(),
                    targetId = stage.stageId.toString(),
                    agent = request.agent,
                    payload = (request.context?.let { mapOf("context" to it) } ?: emptyMap()) +
                        (aiClientConfig?.let {
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
                        "status" to stage.status.name,
                        "needsAiReview" to stage.signals.needsAiReview,
                        "mode" to request.mode
                    )
                )
            ).toResponse()
        } ?: throw SecruxException(ErrorCode.STAGE_NOT_FOUND, "Stage not found")

    private fun ensureStageBelongs(stage: Stage, taskId: UUID) {
        if (stage.taskId != taskId) {
            throw SecruxException(ErrorCode.STAGE_NOT_FOUND, "Stage not found")
        }
    }

    private fun StageMetricsPayload?.toDomain(fallback: StageMetrics? = null): StageMetrics =
        this?.let {
            StageMetrics(
                durationMs = it.durationMs,
                cpuUsage = it.cpuUsage,
                memoryUsageMb = it.memoryUsageMb,
                retryCount = it.retryCount ?: fallback?.retryCount ?: 0,
                artifactSizeBytes = it.artifactSizeBytes
            )
        } ?: (fallback ?: StageMetrics(retryCount = 0))

    private fun StageSignalsPayload?.toDomain(fallback: StageSignals? = null): StageSignals =
        this?.let {
            StageSignals(
                needsAiReview = it.needsAiReview ?: fallback?.needsAiReview ?: false,
                autoFixPossible = it.autoFixPossible ?: fallback?.autoFixPossible ?: false,
                riskDelta = it.riskDelta ?: fallback?.riskDelta,
                hasSink = it.hasSink ?: fallback?.hasSink
            )
        } ?: (fallback ?: StageSignals())

    private fun ResourceLimitsPayload?.toDomain(): ResourceLimits? =
        this?.let { ResourceLimits(cpu = it.cpu, memory = it.memory, timeoutSec = it.timeoutSec) }

    private fun String?.toOffsetDateTime(): OffsetDateTime? =
        this?.let { OffsetDateTime.parse(it) }
}
