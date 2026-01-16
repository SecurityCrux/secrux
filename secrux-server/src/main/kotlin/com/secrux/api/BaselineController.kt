package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.BaselineKind
import com.secrux.dto.BaselineUpsertRequest
import com.secrux.service.BaselineService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.baselineResource

@RestController
@RequestMapping("/projects/{projectId}/baselines")
@Tag(name = "Baseline APIs", description = "Baseline fingerprint management")
class BaselineController(
    private val baselineService: BaselineService,
    private val authorizationService: AuthorizationService
) {

    @PostMapping
    @Operation(summary = "Upsert baseline", description = "Upsert fingerprints for a baseline kind")
    @ApiOperationSupport(order = 1)
    fun upsertBaseline(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @Valid @RequestBody request: BaselineUpsertRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.BASELINE_MANAGE,
            resource = principal.baselineResource(projectId)
        )
        return ApiResponse(data = baselineService.upsertBaseline(principal.tenantId, projectId, request))
    }

    @GetMapping
    @Operation(summary = "List baselines", description = "List all baselines for a project")
    @ApiOperationSupport(order = 2)
    fun listBaselines(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.BASELINE_READ,
            resource = principal.baselineResource(projectId)
        )
        return ApiResponse(data = baselineService.listBaselines(principal.tenantId, projectId))
    }

    @GetMapping("/{kind}")
    @Operation(summary = "Get baseline", description = "Get baseline by kind")
    @ApiOperationSupport(order = 3)
    fun getBaseline(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable projectId: UUID,
        @PathVariable kind: BaselineKind
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.BASELINE_READ,
            resource = principal.baselineResource(projectId),
            context = mapOf("kind" to kind.name)
        )
        return ApiResponse(data = baselineService.getBaseline(principal.tenantId, projectId, kind))
    }

}

