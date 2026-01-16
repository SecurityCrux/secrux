package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.CreateIntellijTaskRequest
import com.secrux.dto.IntellijTaskAiReviewRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.security.taskResource
import com.secrux.service.IntellijTaskAiReviewService
import com.secrux.service.IntellijTaskCommandService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/ideplugins/intellij/tasks")
@Tag(name = "IDE Plugin APIs", description = "IntelliJ plugin integration endpoints")
@Validated
class IdePluginIntellijTaskController(
    private val intellijTaskCommandService: IntellijTaskCommandService,
    private val intellijTaskAiReviewService: IntellijTaskAiReviewService,
    private val authorizationService: AuthorizationService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create IntelliJ task", description = "Create an IDE audit task (IDE_AUDIT)")
    @ApiOperationSupport(order = 1)
    fun createTask(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: CreateIntellijTaskRequest,
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_CREATE,
            resource =
                principal.taskResource(
                    projectId = request.projectId,
                    attributes = mapOf("projectId" to request.projectId),
                ),
            context =
                mapOf(
                    "taskType" to "IDE_AUDIT",
                    "name" to (request.name ?: ""),
                    "branch" to (request.branch ?: ""),
                    "commitSha" to (request.commitSha ?: ""),
                ),
        )
        return ApiResponse(data = intellijTaskCommandService.createTask(principal.tenantId, principal.userId, request))
    }

    @PostMapping("/{taskId}/ai-review")
    @Operation(summary = "Run task AI review", description = "Trigger AI review stage for all OPEN findings in a task")
    @ApiOperationSupport(order = 2)
    fun runAiReview(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @Valid @RequestBody request: IntellijTaskAiReviewRequest,
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_MANAGE,
            resource = principal.findingResource(taskId = taskId),
            context =
                mapOf(
                    "action" to "task-ai-review",
                    "mode" to request.mode,
                    "dataFlowMode" to (request.dataFlowMode ?: ""),
                ),
        )
        return ApiResponse(data = intellijTaskAiReviewService.runAiReviewStage(principal.tenantId, taskId, request))
    }
}

