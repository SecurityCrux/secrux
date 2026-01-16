package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.AiKnowledgeEntryRequest
import com.secrux.dto.AiKnowledgeSearchRequest
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.authResource
import com.secrux.service.AiIntegrationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/ai/knowledge")
@Tag(name = "AI Knowledge Base APIs", description = "Manage tenant RAG knowledge entries")
class AiKnowledgeController(
    private val service: AiIntegrationService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List knowledge entries")
    @ApiOperationSupport(order = 1)
    fun list(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_KNOWLEDGE_READ,
            resource = principal.authResource(type = "ai_knowledge")
        )
        return ApiResponse(data = service.listKnowledgeEntries(principal.tenantId))
    }

    @PostMapping
    @Operation(summary = "Create knowledge entry")
    @ApiOperationSupport(order = 2)
    fun create(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: AiKnowledgeEntryRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_KNOWLEDGE_MANAGE,
            resource = principal.authResource(type = "ai_knowledge")
        )
        return ApiResponse(data = service.createKnowledgeEntry(principal.tenantId, request))
    }

    @PutMapping("/{entryId}")
    @ApiOperationSupport(order = 3)
    fun update(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable entryId: UUID,
        @Valid @RequestBody request: AiKnowledgeEntryRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_KNOWLEDGE_MANAGE,
            resource = principal.authResource(type = "ai_knowledge", resourceId = entryId)
        )
        return ApiResponse(data = service.updateKnowledgeEntry(principal.tenantId, entryId, request))
    }

    @DeleteMapping("/{entryId}")
    @ApiOperationSupport(order = 4)
    fun delete(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable entryId: UUID
    ): ApiResponse<Unit> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_KNOWLEDGE_MANAGE,
            resource = principal.authResource(type = "ai_knowledge", resourceId = entryId)
        )
        service.deleteKnowledgeEntry(principal.tenantId, entryId)
        return ApiResponse()
    }

    @PostMapping("/search")
    @Operation(summary = "Search knowledge entries")
    @ApiOperationSupport(order = 5)
    fun search(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: AiKnowledgeSearchRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.AI_KNOWLEDGE_READ,
            resource = principal.authResource(type = "ai_knowledge")
        )
        return ApiResponse(data = service.searchKnowledge(principal.tenantId, request))
    }
}
