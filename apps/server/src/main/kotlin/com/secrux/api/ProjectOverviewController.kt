package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.ProjectOverviewResponse
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.projectResource
import com.secrux.service.ProjectOverviewService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/projects/{projectId}/overview")
@Tag(name = "Project Overview APIs", description = "Project overview aggregates for the current tenant")
class ProjectOverviewController(
    private val projectOverviewService: ProjectOverviewService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "Project overview", description = "Get overview metrics for a project")
    @ApiOperationSupport(order = 0)
    fun getOverview(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID
    ): ApiResponse<ProjectOverviewResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.PROJECT_READ,
            resource = principal.projectResource(projectId)
        )
        return ApiResponse(data = projectOverviewService.getOverview(principal.tenantId, projectId))
    }
}

