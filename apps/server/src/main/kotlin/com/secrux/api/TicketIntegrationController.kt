package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.TicketProviderConfigUpsertRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.ticketResource
import com.secrux.service.TicketProviderConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/ticket-integrations")
@Tag(name = "Ticket Integration APIs", description = "External ticket provider configuration")
class TicketIntegrationController(
    private val service: TicketProviderConfigService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List ticket provider configs", description = "List configured external ticket providers for tenant")
    @ApiOperationSupport(order = 0)
    fun listConfigs(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_MANAGE,
            resource = principal.ticketResource(projectId = null)
        )
        return ApiResponse(data = service.listConfigs(principal.tenantId))
    }

    @PutMapping("/{provider}")
    @Operation(summary = "Upsert ticket provider config", description = "Create or update ticket provider config by provider key")
    @ApiOperationSupport(order = 1)
    fun upsertConfig(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable provider: String,
        @Valid @RequestBody request: TicketProviderConfigUpsertRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_MANAGE,
            resource = principal.ticketResource(projectId = null)
        )
        return ApiResponse(data = service.upsertConfig(principal.tenantId, provider, request))
    }

    @DeleteMapping("/{provider}")
    @Operation(summary = "Delete ticket provider config")
    @ApiOperationSupport(order = 2)
    fun deleteConfig(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable provider: String
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_MANAGE,
            resource = principal.ticketResource(projectId = null)
        )
        service.deleteConfig(principal.tenantId, provider)
        return ApiResponse<Unit>()
    }
}

