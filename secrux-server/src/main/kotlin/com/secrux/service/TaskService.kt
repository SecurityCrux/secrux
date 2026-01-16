package com.secrux.service

import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.dto.ArtifactSummary
import com.secrux.dto.CreateTaskRequest
import com.secrux.dto.PageResponse
import com.secrux.dto.StageSummary
import com.secrux.dto.TaskSummary
import com.secrux.dto.UpdateTaskRequest
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TaskService(
    private val taskQueryService: TaskQueryService,
    private val taskCommandService: TaskCommandService,
) {
    fun createTask(
        tenantId: UUID,
        request: CreateTaskRequest,
    ): TaskSummary = taskCommandService.createTask(tenantId, request)

    fun getTask(
        tenantId: UUID,
        taskId: UUID,
    ): TaskSummary = taskQueryService.getTask(tenantId, taskId)

    fun listTasks(
        tenantId: UUID,
        projectId: UUID?,
        type: TaskType?,
        excludeType: TaskType?,
        status: TaskStatus?,
        search: String?,
        limit: Int,
        offset: Int,
    ): PageResponse<TaskSummary> = taskQueryService.listTasks(tenantId, projectId, type, excludeType, status, search, limit, offset)

    fun listStages(
        tenantId: UUID,
        taskId: UUID,
    ): List<StageSummary> = taskQueryService.listStages(tenantId, taskId)

    fun listArtifacts(
        tenantId: UUID,
        taskId: UUID,
    ): List<ArtifactSummary> = taskQueryService.listArtifacts(tenantId, taskId)

    fun updateTask(
        tenantId: UUID,
        taskId: UUID,
        request: UpdateTaskRequest,
    ): TaskSummary = taskCommandService.updateTask(tenantId, taskId, request)

    fun assignExecutor(
        tenantId: UUID,
        taskId: UUID,
        executorId: UUID,
    ): TaskSummary = taskCommandService.assignExecutor(tenantId, taskId, executorId)

    fun deleteTask(
        tenantId: UUID,
        taskId: UUID,
    ) = taskCommandService.deleteTask(tenantId, taskId)
}
