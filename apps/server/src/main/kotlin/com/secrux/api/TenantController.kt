package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.Tenant
import com.secrux.dto.TenantResponse
import com.secrux.dto.TenantUpdateRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.userResource
import com.secrux.service.TenantService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tenant")
@Tag(name = "Tenant", description = "Tenant profile management")
class TenantController(
    private val tenantService: TenantService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "Get current tenant", description = "Get tenant profile for the current principal")
    @ApiOperationSupport(order = 1)
    fun getTenant(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<TenantResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_READ,
            resource = principal.userResource()
        )
        return ApiResponse(data = tenantService.getOrCreate(principal.tenantId).toResponse())
    }

    @PatchMapping
    @Operation(summary = "Update current tenant", description = "Update tenant profile for the current principal")
    @ApiOperationSupport(order = 2)
    fun updateTenant(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: TenantUpdateRequest
    ): ApiResponse<TenantResponse> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.USER_MANAGE,
            resource = principal.userResource()
        )
        return ApiResponse(data = tenantService.update(principal.tenantId, request).toResponse())
    }

    private fun Tenant.toResponse(): TenantResponse =
        TenantResponse(
            tenantId = tenantId,
            name = name,
            plan = plan,
            contactEmail = contactEmail,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
}

