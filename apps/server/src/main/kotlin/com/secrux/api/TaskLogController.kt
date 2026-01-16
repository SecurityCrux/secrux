package com.secrux.api

import com.secrux.common.ApiResponse
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.taskResource
import com.secrux.service.TaskLogService
import com.secrux.service.toResponse
import com.secrux.repo.StageRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Min
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@RestController
@RequestMapping("/tasks")
@Tag(name = "Task Log APIs", description = "Streaming and historical task logs")
class TaskLogController(
    private val taskLogService: TaskLogService,
    private val authorizationService: AuthorizationService,
    private val stageRepository: StageRepository
) {

    @GetMapping("/{taskId}/logs")
    @Operation(summary = "List task logs")
    fun listLogs(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @RequestParam(required = false) afterSequence: Long?,
        @RequestParam(defaultValue = "200") @Min(1) limit: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        val chunks = taskLogService.list(taskId, afterSequence, limit.coerceAtMost(1000))
            .map { it.toResponse() }
        return ApiResponse(data = chunks)
    }

    @GetMapping("/{taskId}/logs/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    @Operation(summary = "Stream task logs in real time")
    fun streamLogs(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID
    ): SseEmitter {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        return taskLogService.stream(taskId)
    }

    @GetMapping("/{taskId}/stages/{stageId}/logs")
    @Operation(summary = "List logs for a specific stage")
    fun listStageLogs(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable stageId: UUID,
        @RequestParam(defaultValue = "200") @Min(1) limit: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        val stage =
            stageRepository.findById(stageId, principal.tenantId)
                ?: throw SecruxException(ErrorCode.STAGE_NOT_FOUND, "Stage not found")
        if (stage.taskId != taskId) {
            throw SecruxException(ErrorCode.FORBIDDEN, "Stage does not belong to task")
        }
        val chunks =
            taskLogService.listForStage(taskId, stage, limit.coerceAtMost(1000))
                .map { it.toResponse() }
        return ApiResponse(data = chunks)
    }
}
