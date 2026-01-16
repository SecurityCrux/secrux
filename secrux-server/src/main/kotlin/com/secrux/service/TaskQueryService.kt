package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.dto.PageResponse
import com.secrux.dto.StageSummary
import com.secrux.dto.TaskSummary
import com.secrux.repo.ArtifactRepository
import com.secrux.repo.StageRepository
import com.secrux.repo.TaskRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TaskQueryService(
    private val taskRepository: TaskRepository,
    private val stageRepository: StageRepository,
    private val artifactRepository: ArtifactRepository,
) {
    fun getTask(
        tenantId: UUID,
        taskId: UUID,
    ): TaskSummary {
        val task =
            taskRepository.findById(taskId, tenantId)
                ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        return task.toSummary()
    }

    fun listTasks(
        tenantId: UUID,
        projectId: UUID?,
        type: TaskType?,
        excludeType: TaskType?,
        status: TaskStatus?,
        search: String?,
        limit: Int,
        offset: Int,
    ): PageResponse<TaskSummary> {
        val (items, total) =
            taskRepository.list(
                tenantId = tenantId,
                projectId = projectId,
                type = type,
                excludeType = excludeType,
                status = status,
                search = search,
                limit = limit,
                offset = offset,
            )
        return PageResponse(
            items = items.map { it.toSummary() },
            total = total,
            limit = limit,
            offset = offset,
        )
    }

    fun listStages(
        tenantId: UUID,
        taskId: UUID,
    ): List<StageSummary> {
        taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        return stageRepository.listByTask(taskId, tenantId).map {
            StageSummary(
                stageId = it.stageId,
                taskId = it.taskId,
                type = it.type.name,
                status = it.status.name,
                artifacts = it.artifacts,
                startedAt = it.startedAt?.toString(),
                endedAt = it.endedAt?.toString(),
            )
        }
    }

    fun listArtifacts(
        tenantId: UUID,
        taskId: UUID,
    ): List<com.secrux.dto.ArtifactSummary> {
        taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        return artifactRepository.listByTask(taskId, tenantId).map {
            com.secrux.dto.ArtifactSummary(
                artifactId = it.artifactId,
                stageId = it.stageId,
                uri = it.uri,
                kind = it.kind,
                checksum = it.checksum,
                sizeBytes = it.sizeBytes,
            )
        }
    }
}
