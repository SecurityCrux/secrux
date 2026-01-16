package com.secrux.workflow

import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.events.PlatformEvent
import com.secrux.events.PlatformEventPublisher
import com.secrux.repo.TaskRepository
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkflowState(
    val tenantId: UUID,
    val taskId: UUID,
    val correlationId: String,
    val taskType: TaskType,
    val stages: List<StageType>,
    var stageIndex: Int = 0,
    var inFlightStageId: UUID? = null,
    var inFlightStageType: StageType? = null,
    var completed: Boolean = false
)

@Component
class WorkflowOrchestrator(
    private val stagePlanner: WorkflowStagePlanner,
    private val stageExecutor: WorkflowStageExecutor,
    private val eventPublisher: PlatformEventPublisher,
    private val taskRepository: TaskRepository,
    private val clock: Clock
) {

    private val log = LoggerFactory.getLogger(WorkflowOrchestrator::class.java)
    private val workflows = ConcurrentHashMap<UUID, WorkflowState>()
    private var lastPolled: OffsetDateTime = initialPollTimestamp()
    private val pollLimit: Int = 100

    fun startWorkflow(tenantId: UUID, taskId: UUID) {
        val task = taskRepository.findById(taskId, tenantId)
        if (task == null) {
            LogContext.with(LogContext.TENANT_ID to tenantId, LogContext.TASK_ID to taskId) {
                log.warn("event=workflow_start_ignored reason=task_not_found")
            }
            return
        }
        LogContext.with(
            LogContext.TENANT_ID to tenantId,
            LogContext.TASK_ID to taskId,
            LogContext.CORRELATION_ID to task.correlationId
        ) {
            workflows[taskId] =
                WorkflowState(
                    tenantId = tenantId,
                    taskId = taskId,
                    correlationId = task.correlationId,
                    taskType = task.type,
                    stages = stagePlanner.planStages(task.type)
                )
            log.info("event=workflow_initialized taskType={} stages={}", task.type.name, workflows[taskId]?.stages?.joinToString(","))
        }
    }

    fun cancelWorkflow(taskId: UUID) {
        val state = workflows.remove(taskId) ?: return
        LogContext.with(
            LogContext.TENANT_ID to state.tenantId,
            LogContext.TASK_ID to state.taskId,
            LogContext.CORRELATION_ID to state.correlationId
        ) {
            log.info("event=workflow_canceled")
        }
    }

    @Scheduled(fixedDelay = 5000)
    fun scheduledRun() {
        runCycle()
    }

    fun runCycle() {
        val events = eventPublisher.fetchAfter(lastPolled, pollLimit)
        events.forEach { event ->
            withEventContext(event) {
                ingest(event)
                eventPublisher.markProcessed(event.eventId)
            }
        }
        if (events.isNotEmpty()) {
            lastPolled = events.maxOf { it.createdAt }
        }
        triggerPendingWorkflows()
    }

    private fun triggerPendingWorkflows() {
        workflows.values.filter { !it.completed }.forEach { state ->
            if (state.inFlightStageId != null) {
                return@forEach
            }
            val stageType = state.stages.getOrNull(state.stageIndex)
            if (stageType == null) {
                withWorkflowContext(state) { markTaskSucceeded(state) }
                return@forEach
            }
            withWorkflowContext(state, stageType = stageType) { startStage(state, stageType) }
        }
        workflows.entries.removeIf { it.value.completed }
    }

    fun ingest(event: PlatformEvent) {
        when (event.event) {
            "StageCompleted" -> handleStageEvent(event)
            "StageFailed" -> handleStageEvent(event)
            "StageUpdated" -> handleStageEvent(event)
        }
    }

    private fun handleStageEvent(event: PlatformEvent) {
        val info = parseStageEvent(event) ?: return
        val state = workflows[info.taskId] ?: return
        if (state.completed) return
        withWorkflowContext(state, stageId = info.stageId, stageType = info.stageType) {
            val expectedStage = state.stages.getOrNull(state.stageIndex) ?: return@withWorkflowContext
            if (state.inFlightStageId != info.stageId || state.inFlightStageType != expectedStage) {
                return@withWorkflowContext
            }
            if (info.stageType != expectedStage) {
                log.warn(
                    "event=workflow_stage_event_ignored reason=unexpected_stage expectedStage={} actualStage={}",
                    expectedStage.name,
                    info.stageType.name
                )
                return@withWorkflowContext
            }
            when (info.status) {
                StageStatus.SUCCEEDED, StageStatus.SKIPPED -> {
                    log.info("event=workflow_stage_completed status={} nextIndex={}", info.status.name, state.stageIndex + 1)
                    state.stageIndex += 1
                    state.inFlightStageId = null
                    state.inFlightStageType = null
                    if (state.stageIndex >= state.stages.size) {
                        markTaskSucceeded(state)
                    }
                }

                StageStatus.FAILED -> {
                    log.warn("event=workflow_stage_failed")
                    state.inFlightStageId = null
                    state.inFlightStageType = null
                    markTaskFailed(state)
                }

                else -> {
                    // Ignore intermediate statuses (e.g. RUNNING).
                }
            }
        }
    }

    private data class StageEventInfo(
        val tenantId: UUID?,
        val taskId: UUID,
        val stageId: UUID,
        val stageType: StageType,
        val status: StageStatus
    )

    private fun parseStageEvent(event: PlatformEvent): StageEventInfo? {
        val taskId = (event.payload["task_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val stageId = (event.payload["stage_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val stageTypeRaw = event.payload["type"] as? String ?: return null
        val stageType = runCatching { StageType.valueOf(stageTypeRaw) }.getOrNull() ?: return null
        val statusRaw = event.payload["status"] as? String ?: return null
        val status = runCatching { StageStatus.valueOf(statusRaw) }.getOrNull() ?: return null
        val tenantId = (event.payload["tenant_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (event.event == "StageUpdated" && status != StageStatus.SKIPPED) {
            return null
        }
        return StageEventInfo(
            tenantId = tenantId,
            taskId = taskId,
            stageId = stageId,
            stageType = stageType,
            status = status
        )
    }

    private fun initialPollTimestamp(): OffsetDateTime =
        runCatching { OffsetDateTime.ofInstant(Instant.EPOCH, clock.zone) }
            .getOrElse { OffsetDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC) }

    private fun startStage(
        state: WorkflowState,
        stageType: StageType
    ) {
        val stageId = UUID.randomUUID()
        state.inFlightStageId = stageId
        state.inFlightStageType = stageType
        withWorkflowContext(state, stageId = stageId, stageType = stageType) {
            log.info("event=workflow_stage_started")
            try {
                stageExecutor.executeStage(
                    tenantId = state.tenantId,
                    taskId = state.taskId,
                    taskType = state.taskType,
                    stageType = stageType,
                    stageId = stageId
                )
            } catch (ex: Exception) {
                log.error("event=workflow_stage_failed_unhandled error={}", ex.message, ex)
                stageExecutor.recordUnhandledStageFailure(
                    tenantId = state.tenantId,
                    taskId = state.taskId,
                    correlationId = state.correlationId,
                    stageType = stageType,
                    stageId = stageId,
                    ex = ex
                )
                state.inFlightStageId = null
                state.inFlightStageType = null
                markTaskFailed(state)
            }
        }
    }

    private fun markTaskFailed(state: WorkflowState) {
        state.completed = true
        taskRepository.updateStatus(state.taskId, state.tenantId, TaskStatus.FAILED.name)
    }

    private fun markTaskSucceeded(state: WorkflowState) {
        state.completed = true
        taskRepository.updateStatus(state.taskId, state.tenantId, TaskStatus.SUCCEEDED.name)
    }

    private inline fun <T> withWorkflowContext(
        state: WorkflowState,
        stageId: UUID? = null,
        stageType: StageType? = null,
        block: () -> T
    ): T =
        LogContext.with(
            LogContext.TENANT_ID to state.tenantId,
            LogContext.TASK_ID to state.taskId,
            LogContext.CORRELATION_ID to state.correlationId,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to stageType?.name,
            block = block
        )

    private inline fun <T> withEventContext(event: PlatformEvent, block: () -> T): T {
        val tenantId =
            event.tenantId
                ?: (event.payload["tenant_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val taskId = (event.payload["task_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val stageId = (event.payload["stage_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val stageTypeRaw = event.payload["type"] as? String
        val stageType = stageTypeRaw?.let { runCatching { StageType.valueOf(it) }.getOrNull() }
        return LogContext.with(
            LogContext.CORRELATION_ID to event.correlationId,
            LogContext.TENANT_ID to tenantId,
            LogContext.TASK_ID to taskId,
            LogContext.STAGE_ID to stageId,
            LogContext.STAGE_TYPE to stageType?.name,
            block = block
        )
    }
}
