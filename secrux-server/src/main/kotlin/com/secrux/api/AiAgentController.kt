package com.secrux.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ErrorCode
import com.secrux.common.ApiResponse
import com.secrux.common.SecruxException
import com.secrux.dto.AiAgentConfigRequest
import com.secrux.dto.AiAgentUploadRequest
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
@RequestMapping("/ai/agents")
@Tag(name = "AI Agent APIs", description = "Manage AI agents orchestrated by the platform")
class AiAgentController(
    private val service: AiIntegrationService,
    private val authorizationService: AuthorizationService,
    private val objectMapper: ObjectMapper
) {

    @GetMapping
    @Operation(summary = "List AI agents")
    @ApiOperationSupport(order = 1)
    fun listAgents(@AuthenticationPrincipal principal: SecruxPrincipal): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_AGENT_READ,
            resource = principal.authResource(type = "ai_agent")
        )
        return ApiResponse(data = service.listAgents(principal.tenantId))
    }

    @PostMapping
    @Operation(summary = "Create AI agent")
    @ApiOperationSupport(order = 2)
    fun createAgent(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: AiAgentConfigRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_AGENT_MANAGE,
            resource = principal.authResource(type = "ai_agent")
        )
        return ApiResponse(data = service.createAgent(principal.tenantId, request))
    }

    @PutMapping("/{agentId}")
    @ApiOperationSupport(order = 3)
    fun updateAgent(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable agentId: UUID,
        @Valid @RequestBody request: AiAgentConfigRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_AGENT_MANAGE,
            resource = principal.authResource(type = "ai_agent", resourceId = agentId)
        )
        return ApiResponse(data = service.updateAgent(principal.tenantId, agentId, request))
    }

    @DeleteMapping("/{agentId}")
    @ApiOperationSupport(order = 4)
    fun deleteAgent(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable agentId: UUID
    ): ApiResponse<Unit> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_AGENT_MANAGE,
            resource = principal.authResource(type = "ai_agent", resourceId = agentId)
        )
        service.deleteAgent(principal.tenantId, agentId)
        return ApiResponse()
    }

    @PostMapping(
        "/upload",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE]
    )
    @ApiOperationSupport(order = 5)
    @Operation(summary = "Upload agent plugin", description = "Upload a ZIP/TAR archive containing the agent implementation.")
    fun uploadAgent(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestPart("file") file: MultipartFile,
        @RequestPart("name") name: String,
        @RequestPart("kind") kind: String,
        @RequestPart(value = "entrypoint", required = false) entrypoint: String?,
        @RequestPart(value = "stageTypes", required = false) stageTypes: String?,
        @RequestPart(value = "mcpProfileId", required = false) mcpProfileId: String?,
        @RequestPart(value = "enabled", required = false) enabled: Boolean?,
        @RequestPart(value = "params", required = false) params: String?
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_AGENT_MANAGE,
            resource = principal.authResource(type = "ai_agent")
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
        val stageTypeList: List<String> =
            if (stageTypes.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    objectMapper.readValue(stageTypes)
                } catch (ex: Exception) {
                    throw SecruxException(ErrorCode.VALIDATION_ERROR, "stageTypes must be a JSON array of strings", cause = ex)
                }
            }
        val mcpUuid =
            if (mcpProfileId.isNullOrBlank()) {
                null
            } else {
                try {
                    UUID.fromString(mcpProfileId)
                } catch (ex: IllegalArgumentException) {
                    throw SecruxException(ErrorCode.VALIDATION_ERROR, "Invalid MCP profile id", cause = ex)
                }
            }
        val request = AiAgentUploadRequest(
            name = name,
            kind = kind,
            entrypoint = entrypoint,
            stageTypes = stageTypeList,
            mcpProfileId = mcpUuid,
            enabled = enabled ?: true,
            params = paramsMap
        )
        return ApiResponse(data = service.uploadAgent(principal.tenantId, request, file))
    }
}
