package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.projectResource
import com.secrux.service.FindingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/projects/{projectId}/findings")
@Tag(name = "Project Findings APIs", description = "Browse findings within a project")
class ProjectFindingController(
    private val findingService: FindingService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List findings by project", description = "Paginated findings within a project")
    @ApiOperationSupport(order = 1)
    fun listByProject(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: Severity?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.projectResource(projectId = projectId)
        )
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return ApiResponse(
            data = findingService.listFindingsByProject(
                tenantId = principal.tenantId,
                projectId = projectId,
                status = status,
                severity = severity,
                search = search,
                limit = safeLimit,
                offset = safeOffset
            )
        )
    }
}
