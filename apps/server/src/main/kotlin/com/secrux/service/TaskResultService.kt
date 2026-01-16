package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.LogLevel
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.TaskType
import com.secrux.repo.StageRepository
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

data class ExecutorTaskResultPayload(
    val taskId: UUID,
    val stageId: UUID? = null,
    val stageType: StageType? = null,
    val success: Boolean,
    val exitCode: Int?,
    val log: String?,
    val result: String?,
    val artifacts: Map<String, String>? = null,
    val runLog: String?,
    val error: String?
)

@Service
class TaskResultService(
    private val taskRepository: TaskRepository,
    private val stageRepository: StageRepository,
    private val stageLifecycle: StageLifecycle,
    private val taskLogService: TaskLogService,
    private val artifactStore: TaskResultArtifactStore,
    private val stageFactory: TaskResultStageFactory,
    private val clock: Clock
) {

    fun handleResult(payload: ExecutorTaskResultPayload) {
        val task = taskRepository.find(payload.taskId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task ${payload.taskId} not found for result ingestion")
        val engineId =
            when (task.type) {
                TaskType.SCA_CHECK -> task.engine ?: "trivy"
                else -> task.engine ?: "semgrep"
            }
        val stageId = payload.stageId ?: UUID.randomUUID()
        val stageType = payload.stageType ?: StageType.SCAN_EXEC
        val existingStage = stageRepository.findById(stageId, task.tenantId)
        val startedAt = existingStage?.startedAt ?: OffsetDateTime.now(clock)
        val artifacts = artifactStore.persist(task, stageId, stageType, payload)
        val hasRequiredOutput =
            when (stageType) {
                StageType.SCAN_EXEC -> artifacts.resultPath != null
                else -> true
        }
        val stageStatus = if (payload.success && hasRequiredOutput) StageStatus.SUCCEEDED else StageStatus.FAILED
        val now = OffsetDateTime.now(clock)
        val stage =
            stageFactory.build(
                task = task,
                stageId = stageId,
                stageType = stageType,
                engineId = engineId,
                startedAt = startedAt,
                endedAt = now,
                status = stageStatus,
                artifacts = artifacts,
                payload = payload
            )
        stageLifecycle.persist(stage, task.correlationId)

        if (!payload.log.isNullOrBlank() && engineId.lowercase() != "semgrep") {
            taskLogService.captureStageLog(
                taskId = task.taskId,
                stageId = stageId,
                stageType = stageType,
                rawContent = payload.log,
                level = if (stageStatus == StageStatus.FAILED) LogLevel.ERROR else LogLevel.INFO
            )
        }
        val summary =
            buildString {
                append("Executor reported exitCode=${payload.exitCode ?: -1} (engine=$engineId)")
                if ((payload.exitCode ?: 0) == 1 && payload.success) {
                    append(" (non-fatal)")
                }
                payload.error?.takeIf { it.isNotBlank() }?.let { append(" error=$it") }
                append(" artifacts=${artifacts.artifactList.size}")
            }
        taskLogService.logStageEvent(
            taskId = task.taskId,
            stageId = stageId,
            stageType = stageType,
            level = if (stageStatus == StageStatus.SUCCEEDED) LogLevel.INFO else LogLevel.ERROR,
            message = summary
        )
    }
}
