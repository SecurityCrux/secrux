package com.secrux.service

import com.secrux.domain.Stage
import com.secrux.domain.StageStatus
import com.secrux.domain.TaskStatus
import com.secrux.events.PlatformEvent
import com.secrux.events.PlatformEventPublisher
import com.secrux.repo.StageRepository
import com.secrux.repo.TaskRepository
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

@Component
open class StageLifecycle(
    private val stageRepository: StageRepository,
    private val eventPublisher: PlatformEventPublisher,
    private val taskRepository: TaskRepository,
    private val clock: Clock
) {

    private val log = LoggerFactory.getLogger(StageLifecycle::class.java)

    fun persist(stage: Stage, correlationId: String) {
        LogContext.with(
            LogContext.TENANT_ID to stage.tenantId,
            LogContext.TASK_ID to stage.taskId,
            LogContext.CORRELATION_ID to correlationId,
            LogContext.STAGE_ID to stage.stageId,
            LogContext.STAGE_TYPE to stage.type.name
        ) {
            stageRepository.upsert(stage)
            if (stage.status == StageStatus.FAILED) {
                taskRepository.updateStatus(stage.taskId, stage.tenantId, TaskStatus.FAILED.name)
            }
            publishEvent(stage, correlationId)
        }
    }

    private fun publishEvent(stage: Stage, correlationId: String) {
        val event = when (stage.status) {
            StageStatus.RUNNING -> "StageStarted"
            StageStatus.SUCCEEDED -> "StageCompleted"
            StageStatus.FAILED -> "StageFailed"
            else -> "StageUpdated"
        }
        val now = OffsetDateTime.now(clock)
        val payload = mapOf(
            "stage_id" to stage.stageId.toString(),
            "task_id" to stage.taskId.toString(),
            "tenant_id" to stage.tenantId.toString(),
            "type" to stage.type.name,
            "status" to stage.status.name,
            "artifacts" to stage.artifacts,
            "signals" to stage.signals,
            "metrics" to stage.metrics
        )
        log.debug("event=stage_event_publish eventType={}", event)
        eventPublisher.publish(
            PlatformEvent(
                event = event,
                correlationId = correlationId,
                payload = payload,
                tenantId = stage.tenantId,
                createdAt = now
            )
        )
    }
}
