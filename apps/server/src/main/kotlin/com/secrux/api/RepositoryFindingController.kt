package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.repositoryResource
import com.secrux.service.FindingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/projects/{projectId}/repos/{repoId}/findings")
@Tag(name = "Repository Findings APIs", description = "Browse findings within a repository")
class RepositoryFindingController(
    private val findingService: FindingService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List findings by repository", description = "Paginated findings within a repository")
    @ApiOperationSupport(order = 1)
    fun listByRepo(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @PathVariable repoId: UUID,
        @RequestParam(required = false) status: FindingStatus?,
        @RequestParam(required = false) severity: Severity?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.FINDING_READ,
            resource = principal.repositoryResource(projectId = projectId, repoId = repoId)
        )
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        return ApiResponse(
            data = findingService.listFindingsByRepo(
                tenantId = principal.tenantId,
                projectId = projectId,
                repoId = repoId,
                status = status,
                severity = severity,
                search = search,
                limit = safeLimit,
                offset = safeOffset
            )
        )
    }
}

