package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/sca/issues")
@Tag(name = "SCA Issue APIs", description = "Manage dependency vulnerabilities discovered by SCA engines")
class ScaIssueQueryController(
    private val service: ScaIssueService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping("/{issueId}")
    @Operation(summary = "Get SCA issue detail", description = "Return a single SCA issue by ID")
    @ApiOperationSupport(order = 0)
    fun getIssue(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable issueId: UUID
    ): ApiResponse<*> {
        val issue = service.getIssueDetail(principal.tenantId, issueId)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource(taskId = issue.taskId)
        )
        return ApiResponse(data = issue)
    }

    @PatchMapping("/{issueId}")
    @Operation(summary = "Update SCA issue status", description = "Change the workflow status for an SCA issue")
    @ApiOperationSupport(order = 1)
    fun updateStatus(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable issueId: UUID,
        @Valid @RequestBody request: ScaIssueStatusUpdateRequest
    ): ApiResponse<*> {
        val issue = service.getIssueDetail(principal.tenantId, issueId)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_UPDATE,
            resource = principal.taskResource(taskId = issue.taskId),
            context = mapOf("status" to request.status.name)
        )
        return ApiResponse(data = service.updateStatus(principal, issueId, request.status))
    }
}
