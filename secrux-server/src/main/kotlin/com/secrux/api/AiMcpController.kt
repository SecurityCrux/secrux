package com.secrux.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.dto.AiMcpConfigRequest
import com.secrux.dto.AiMcpUploadRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.authResource
import com.secrux.service.AiIntegrationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/ai/mcps")
@Tag(name = "AI MCP APIs", description = "Manage MCP profiles for AI agents")
class AiMcpController(
    private val service: AiIntegrationService,
    private val authorizationService: AuthorizationService,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    @Operation(summary = "List MCP profiles")
    @ApiOperationSupport(order = 1)
    fun listMcps(@AuthenticationPrincipal principal: SecruxPrincipal): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_MCP_READ,
            resource = principal.authResource(type = "ai_mcp")
        )
        return ApiResponse(data = service.listMcps(principal.tenantId))
    }

    @PostMapping
    @Operation(summary = "Create MCP profile")
    @ApiOperationSupport(order = 2)
    fun createMcp(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: AiMcpConfigRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_MCP_MANAGE,
            resource = principal.authResource(type = "ai_mcp")
        )
        return ApiResponse(data = service.createMcp(principal.tenantId, request))
    }

    @PutMapping("/{profileId}")
    @ApiOperationSupport(order = 3)
    fun updateMcp(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable profileId: UUID,
        @Valid @RequestBody request: AiMcpConfigRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_MCP_MANAGE,
            resource = principal.authResource(type = "ai_mcp", resourceId = profileId)
        )
        return ApiResponse(data = service.updateMcp(principal.tenantId, profileId, request))
    }

    @DeleteMapping("/{profileId}")
    @ApiOperationSupport(order = 4)
    fun deleteMcp(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable profileId: UUID
    ): ApiResponse<Unit> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_MCP_MANAGE,
            resource = principal.authResource(type = "ai_mcp", resourceId = profileId)
        )
        service.deleteMcp(principal.tenantId, profileId)
        return ApiResponse()
    }

    @PostMapping(
        "/upload",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    @Operation(summary = "Upload local MCP package", description = "Upload an archive containing MCP implementation")
    @ApiOperationSupport(order = 6)
    fun uploadMcp(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestPart("file") file: MultipartFile,
        @RequestPart("name") name: String,
        @RequestPart(value = "entrypoint", required = false) entrypoint: String?,
        @RequestPart(value = "enabled", required = false) enabled: Boolean?,
        @RequestPart(value = "params", required = false) params: String?
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_MCP_MANAGE,
            resource = principal.authResource(type = "ai_mcp")
        )
        val paramsMap: Map<String, Any?> =
            if (params.isNullOrBlank()) {
                emptyMap()
            } else {
                try {
                    objectMapper.readValue(params)
                } catch (ex: Exception) {
                    throw SecruxException(ErrorCode.VALIDATION_ERROR, "params must be valid JSON", cause = ex)
                }
            }
        val request = AiMcpUploadRequest(
            name = name,
            entrypoint = entrypoint,
            enabled = enabled ?: true,
            params = paramsMap
        )
        return ApiResponse(data = service.uploadMcp(principal.tenantId, request, file))
    }
}
