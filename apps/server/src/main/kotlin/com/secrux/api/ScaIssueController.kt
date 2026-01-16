package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.dto.ScaIssueStatusUpdateRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.taskResource
import com.secrux.service.ScaIssueService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/sca/tasks/{taskId}/issues")
@Tag(name = "SCA Issue APIs", description = "Manage dependency vulnerabilities discovered by SCA engines")
class ScaIssueController(
    private val service: ScaIssueService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List SCA issues", description = "Paginated list of SCA issues for a task")
    @ApiOperationSupport(order = 0)
    fun list(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: Severity?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return ApiResponse(
            data = service.listIssuesByTask(
                tenantId = principal.tenantId,
                taskId = taskId,
                status = status,
                severity = severity,
                search = search,
                limit = safeLimit,
                offset = safeOffset
            )
        )
    }

    @GetMapping("/{issueId}")
    @Operation(summary = "Get SCA issue detail", description = "Return a single SCA issue within a task")
    @ApiOperationSupport(order = 1)
    fun getIssue(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable issueId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = taskId)
        )
        return ApiResponse(data = service.getIssueDetail(principal.tenantId, taskId, issueId))
    }

    @PatchMapping("/{issueId}")
    @Operation(summary = "Update SCA issue status", description = "Change the workflow status for an SCA issue")
    @ApiOperationSupport(order = 2)
    fun updateStatus(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable taskId: UUID,
        @PathVariable issueId: UUID,
        @Valid @RequestBody request: ScaIssueStatusUpdateRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_UPDATE,
            resource = principal.taskResource(taskId = taskId),
            context = mapOf("status" to request.status.name)
        )
        return ApiResponse(data = service.updateStatus(principal, taskId, issueId, request.status))
    }
}
