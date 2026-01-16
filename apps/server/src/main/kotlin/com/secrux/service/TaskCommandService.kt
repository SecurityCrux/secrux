package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Task
import com.secrux.domain.TaskStatus
import com.secrux.dto.CreateTaskRequest
import com.secrux.dto.TaskSummary
import com.secrux.dto.UpdateTaskRequest
import com.secrux.repo.TaskRepository
import com.secrux.workflow.TaskWorkflowOrchestrator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class TaskCommandService(
    private val taskRepository: TaskRepository,
    private val executorAvailabilityService: ExecutorAvailabilityService,
    private val taskSpecMapper: TaskSpecMapper,
    private val orchestrator: TaskWorkflowOrchestrator,
    private val semgrepProTokenService: SemgrepProTokenService,
    private val clock: Clock,
) {
    @Transactional
    fun createTask(
        tenantId: UUID,
        request: CreateTaskRequest,
    ): TaskSummary {
        val resolvedExecutorId = executorAvailabilityService.resolveOptional(tenantId, request.executorId)
        val semgrepState = semgrepProTokenService.resolve(request.spec.engineOptions?.semgrep, null)
        val createdAt = OffsetDateTime.now(clock)
        val name =
            request.name?.trim()?.takeIf { it.isNotBlank() }
                ?: request.correlationId?.trim()?.takeIf { it.isNotBlank() }
                ?: "${request.type.name} ${createdAt.toLocalDateTime()}"
        val taskId = UUID.randomUUID()
        val task =
            Task(
                taskId = taskId,
                tenantId = tenantId,
                projectId = request.projectId,
                repoId = request.repoId,
                executorId = resolvedExecutorId,
                type = request.type,
                spec = taskSpecMapper.toDomain(request.spec),
                status = TaskStatus.PENDING,
                owner = request.owner,
                name = name,
                correlationId = taskId.toString(),
                sourceRefType = request.sourceRefType,
                sourceRef = request.sourceRef,
                commitSha = request.commitSha,
                engine = TaskEngineRegistry.resolveEngine(request.type, request.engine),
                semgrepProEnabled = semgrepState.enabled,
                semgrepTokenCipher = semgrepState.cipher,
                semgrepTokenExpiresAt = semgrepState.expiresAt,
                createdAt = createdAt,
                updatedAt = null,
            )
        taskRepository.insert(task)
        if (task.executorId != null) {
            orchestrator.start(task)
        }
        return task.toSummary()
    }

    @Transactional
    fun updateTask(
        tenantId: UUID,
        taskId: UUID,
        request: UpdateTaskRequest,
    ): TaskSummary {
        val existing =
            taskRepository.findById(taskId, tenantId)
                ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        val desiredName =
            request.name?.let { raw ->
                val trimmed = raw.trim()
                if (trimmed.isBlank()) {
                    throw SecruxException(ErrorCode.VALIDATION_ERROR, "Task name cannot be blank")
                }
                trimmed
            } ?: existing.name
        val resolvedExecutorId = executorAvailabilityService.resolveOptional(tenantId, request.executorId)
        val semgrepState = semgrepProTokenService.resolve(request.spec.engineOptions?.semgrep, existing)
        val updated =
            existing.copy(
                projectId = request.projectId,
                repoId = request.repoId,
                executorId = resolvedExecutorId ?: existing.executorId,
                type = request.type,
                spec = taskSpecMapper.toDomain(request.spec),
                owner = request.owner,
                name = desiredName,
                sourceRefType = request.sourceRefType,
                sourceRef = request.sourceRef,
                commitSha = request.commitSha,
                engine = TaskEngineRegistry.resolveEngine(request.type, request.engine),
                semgrepProEnabled = semgrepState.enabled,
                semgrepTokenCipher = semgrepState.cipher,
                semgrepTokenExpiresAt = semgrepState.expiresAt,
                updatedAt = OffsetDateTime.now(clock),
            )
        taskRepository.update(updated)
        return updated.toSummary()
    }

    @Transactional
    fun assignExecutor(
        tenantId: UUID,
        taskId: UUID,
        executorId: UUID,
    ): TaskSummary {
        val task =
            taskRepository.findById(taskId, tenantId)
                ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        executorAvailabilityService.ensureAvailable(tenantId, executorId)
        val updated =
            task.copy(
                executorId = executorId,
                updatedAt = OffsetDateTime.now(clock),
            )
        taskRepository.update(updated)
        orchestrator.start(updated)
        return updated.toSummary()
    }

    @Transactional
    fun deleteTask(
        tenantId: UUID,
        taskId: UUID,
    ) {
        val task =
            taskRepository.findById(taskId, tenantId)
                ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PENDING) {
            taskRepository.updateStatus(taskId, tenantId, TaskStatus.CANCELED.name)
        }
        taskRepository.softDelete(taskId, tenantId)
        orchestrator.cancel(taskId)
    }
}
