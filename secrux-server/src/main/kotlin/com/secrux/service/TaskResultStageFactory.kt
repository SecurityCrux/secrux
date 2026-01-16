package com.secrux.service

import com.secrux.domain.Stage
import com.secrux.domain.StageMetrics
import com.secrux.domain.StageSignals
import com.secrux.domain.StageSpec
import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import com.secrux.domain.Task
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Component
class TaskResultStageFactory {

    fun build(
        task: Task,
        stageId: UUID,
        stageType: StageType,
        engineId: String,
        startedAt: OffsetDateTime,
        endedAt: OffsetDateTime,
        status: StageStatus,
        artifacts: PersistedExecutionArtifacts,
        payload: ExecutorTaskResultPayload
    ): Stage {
        val durationMs = runCatching { Duration.between(startedAt, endedAt).toMillis() }.getOrNull()
        return Stage(
            stageId = stageId,
            tenantId = task.tenantId,
            taskId = task.taskId,
            type = stageType,
            spec =
                StageSpec(
                    version = "v1",
                    inputs =
                        mapOf(
                            "engine" to engineId,
                            "executorId" to (task.executorId?.toString() ?: "unassigned")
                        ),
                    params =
                        mapOf(
                            "exitCode" to (payload.exitCode ?: -1),
                            "error" to payload.error
                        )
                ),
            status = status,
            metrics = StageMetrics(durationMs = durationMs, artifactSizeBytes = artifacts.totalSize),
            signals = StageSignals(needsAiReview = false),
            artifacts = artifacts.artifactList,
            startedAt = startedAt,
            endedAt = endedAt
        )
    }
}

