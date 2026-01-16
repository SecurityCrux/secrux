package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.PageResponse
import com.secrux.dto.OverviewFindingItem
import com.secrux.dto.OverviewSummaryResponse
import com.secrux.dto.OverviewTaskItem
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.security.projectResource
import com.secrux.security.taskResource
import com.secrux.service.OverviewService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/overview")
@Tag(name = "Overview APIs", description = "Dashboard overview aggregates for the current tenant")
class OverviewController(
    private val overviewService: OverviewService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping("/summary")
    @Operation(summary = "Overview summary", description = "Get dashboard metrics for the current tenant")
    @ApiOperationSupport(order = 0)
    fun getSummary(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(defaultValue = "7d") window: String
    ): ApiResponse<OverviewSummaryResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.PROJECT_READ,
            resource = principal.projectResource()
        )
        return ApiResponse(data = overviewService.getSummary(principal.tenantId, window))
    }

    @GetMapping("/tasks/recent")
    @Operation(summary = "Recent tasks", description = "List recent tasks for dashboard")
    @ApiOperationSupport(order = 1)
    fun listRecentTasks(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<PageResponse<OverviewTaskItem>> {
        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource()
        )
        return ApiResponse(data = overviewService.listRecentTasks(principal.tenantId, safeLimit, safeOffset))
    }

    @GetMapping("/tasks/stuck")
    @Operation(summary = "Stuck tasks", description = "List tasks that have been pending/running longer than the threshold")
    @ApiOperationSupport(order = 2)
    fun listStuckTasks(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(defaultValue = "1800") thresholdSeconds: Long,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<PageResponse<OverviewTaskItem>> {
        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)
        val safeThreshold = thresholdSeconds.coerceIn(60, 7 * 24 * 3600)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TASK_READ,
            resource = principal.taskResource()
        )
        return ApiResponse(data = overviewService.listStuckTasks(principal.tenantId, safeThreshold, safeLimit, safeOffset))
    }

    @GetMapping("/findings/top")
    @Operation(summary = "Top findings", description = "List high severity unresolved findings for dashboard")
    @ApiOperationSupport(order = 3)
    fun listTopFindings(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<PageResponse<OverviewFindingItem>> {
        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.findingResource()
        )
        return ApiResponse(data = overviewService.listTopFindings(principal.tenantId, safeLimit, safeOffset))
    }
}
