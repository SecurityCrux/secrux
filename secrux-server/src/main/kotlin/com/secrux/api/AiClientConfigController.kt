package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.AiClientConfigRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.authResource
import com.secrux.service.AiClientConfigService
import com.secrux.service.AiProviderCatalog
import com.secrux.dto.AiProviderTemplateResponse
import com.secrux.service.AiProviderTemplate
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/ai-clients")
@Tag(name = "AI Client APIs", description = "AI client configuration management")
class AiClientConfigController(
    private val service: AiClientConfigService,
    private val authorizationService: AuthorizationService,
    private val providerCatalog: AiProviderCatalog
) {

    @GetMapping("/providers")
    @Operation(summary = "List recommended AI providers", description = "Predefined metadata for common AI APIs.")
    @ApiOperationSupport(order = 0)
    fun listProviderTemplates(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_CLIENT_READ,
            resource = principal.authResource("ai_client")
        )
        return ApiResponse(
            data = providerCatalog.listTemplates().map { it.toResponse() }
        )
    }

    @GetMapping
    @Operation(summary = "List AI clients", description = "List configured AI clients for tenant")
    @ApiOperationSupport(order = 1)
    fun listConfigs(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_CLIENT_READ,
            resource = principal.authResource("ai_client")
        )
        return ApiResponse(data = service.listConfigs(principal.tenantId))
    }

    @PostMapping
    @Operation(summary = "Create AI client config")
    @ApiOperationSupport(order = 2)
    fun createConfig(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: AiClientConfigRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_CLIENT_MANAGE,
            resource = principal.authResource("ai_client")
        )
        return ApiResponse(data = service.createConfig(principal.tenantId, request))
    }

    @PutMapping("/{configId}")
    @Operation(summary = "Update AI client config")
    @ApiOperationSupport(order = 3)
    fun updateConfig(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable configId: UUID,
        @Valid @RequestBody request: AiClientConfigRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_CLIENT_MANAGE,
            resource = principal.authResource("ai_client")
        )
        return ApiResponse(data = service.updateConfig(principal.tenantId, configId, request))
    }

    @DeleteMapping("/{configId}")
    @Operation(summary = "Delete AI client config")
    @ApiOperationSupport(order = 4)
    fun deleteConfig(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable configId: UUID
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_CLIENT_MANAGE,
            resource = principal.authResource("ai_client")
        )
        service.deleteConfig(principal.tenantId, configId)
        return ApiResponse<Unit>()
    }
}

private fun AiProviderTemplate.toResponse() =
    AiProviderTemplateResponse(
        provider = provider,
        name = name,
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        regions = regions,
        models = models,
        docsUrl = docsUrl,
        description = description
    )
