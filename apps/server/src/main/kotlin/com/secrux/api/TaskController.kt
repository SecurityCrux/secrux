package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.dto.AssignExecutorRequest
import com.secrux.dto.CreateTaskRequest
import com.secrux.dto.UpdateTaskRequest
import com.secrux.service.TaskService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.taskResource

@RestController
@RequestMapping("/tasks")
@Tag(name = "Task APIs", description = "Security task orchestration endpoints")
@Validated
class TaskController(
    private val taskService: TaskService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List tasks", description = "List tasks for the current tenant")
    @ApiOperationSupport(order = 0)
    fun listTasks(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) type: TaskType?,
        @RequestParam(required = false) excludeType: TaskType?,
        @RequestParam(required = false) status: TaskStatus?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<*> {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(projectId = projectId)
        )
        return ApiResponse(
            data = taskService.listTasks(
                tenantId = principal.tenantId,
                projectId = projectId,
                type = type,
                excludeType = excludeType,
                status = status,
                search = search,
                limit = safeLimit,
                offset = safeOffset
            )
        )
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create task", description = "Create new security task and start workflow")
    @ApiOperationSupport(order = 1)
    fun createTask(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: CreateTaskRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_CREATE,
            resource = principal.taskResource(
                projectId = request.projectId,
                attributes = mapOf("projectId" to request.projectId)
            ),
            context = mapOf(
                "taskType" to request.type.name,
                "taskName" to request.name
            )
        )
        return ApiResponse(data = taskService.createTask(principal.tenantId, request))
    }

    @PutMapping("/{taskId}")
    @Operation(summary = "Update task", description = "Update task specification and metadata")
    @ApiOperationSupport(order = 2)
    fun updateTask(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: UpdateTaskRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_UPDATE,
            resource = principal.taskResource(
                taskId = taskId,
                projectId = request.projectId
            ),
            context = mapOf("projectId" to request.projectId)
        )
        return ApiResponse(data = taskService.updateTask(principal.tenantId, taskId, request))
    }

    @PostMapping("/{taskId}/assign")
    @Operation(summary = "Assign executor", description = "Bind an executor to a task to kick off execution")
    @ApiOperationSupport(order = 3)
    fun assignExecutor(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: AssignExecutorRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_UPDATE,
            resource = principal.taskResource(taskId = taskId)
        )
        return ApiResponse(data = taskService.assignExecutor(principal.tenantId, taskId, request.executorId))
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "Get task", description = "Fetch task summary by id")
    @ApiOperationSupport(order = 3)
    fun getTask(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        return ApiResponse(data = taskService.getTask(principal.tenantId, taskId))
    }

    @GetMapping("/{taskId}/stages")
    @Operation(summary = "List stages", description = "List stages for a task")
    @ApiOperationSupport(order = 4)
    fun listStages(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_STAGE_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        return ApiResponse(data = taskService.listStages(principal.tenantId, taskId))
    }

    @GetMapping("/{taskId}/artifacts")
    @Operation(summary = "List artifacts", description = "List artifacts for task")
    @ApiOperationSupport(order = 5)
    fun listArtifacts(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_ARTIFACT_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        return ApiResponse(data = taskService.listArtifacts(principal.tenantId, taskId))
    }

    @DeleteMapping("/{taskId}")
    @Operation(summary = "Delete task", description = "Soft delete task and cancel remaining workflow")
    @ApiOperationSupport(order = 6)
    fun deleteTask(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_DELETE,
            resource = principal.taskResource(taskId = taskId)
        )
        taskService.deleteTask(principal.tenantId, taskId)
        return ApiResponse(data = mapOf("taskId" to taskId))
    }
}
