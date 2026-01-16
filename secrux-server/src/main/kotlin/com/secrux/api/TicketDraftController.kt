package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.dto.TicketDraftItemsRequest
import com.secrux.dto.TicketDraftAiApplyRequest
import com.secrux.dto.TicketDraftAiRequest
import com.secrux.dto.TicketDraftUpdateRequest
import com.secrux.domain.TicketDraftItemType
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.findingResource
import com.secrux.security.taskResource
import com.secrux.security.ticketResource
import com.secrux.service.TicketDraftService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/ticket-drafts")
@Tag(name = "Ticket Draft APIs", description = "Draft basket for creating tickets from findings and SCA issues")
class TicketDraftController(
    private val ticketDraftService: TicketDraftService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping("/current")
    @Operation(summary = "Get current ticket draft")
    @ApiOperationSupport(order = 0)
    fun getCurrent(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        val draft = ticketDraftService.getOrCreateCurrent(principal)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_READ,
            resource = principal.ticketResource(projectId = draft.projectId)
        )
        return ApiResponse(data = draft)
    }

    @PostMapping("/current/items")
    @Operation(summary = "Add items to current draft")
    @ApiOperationSupport(order = 1)
    fun addItems(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: TicketDraftItemsRequest
    ): ApiResponse<*> {
        if (request.items.any { it.type == TicketDraftItemType.FINDING }) {
            authorizationService.require(
                principal = principal,
                action = AuthorizationAction.FINDING_READ,
                resource = principal.findingResource()
            )
        }
        if (request.items.any { it.type == TicketDraftItemType.SCA_ISSUE }) {
            authorizationService.require(
                principal = principal,
                action = AuthorizationAction.TASK_READ,
                resource = principal.taskResource()
            )
        }
        return ApiResponse(data = ticketDraftService.addItems(principal, request.items))
    }

    @DeleteMapping("/current/items")
    @Operation(summary = "Remove items from current draft")
    @ApiOperationSupport(order = 2)
    fun removeItems(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: TicketDraftItemsRequest
    ): ApiResponse<*> {
        if (request.items.any { it.type == TicketDraftItemType.FINDING }) {
            authorizationService.require(
                principal = principal,
                action = AuthorizationAction.FINDING_READ,
                resource = principal.findingResource()
            )
        }
        if (request.items.any { it.type == TicketDraftItemType.SCA_ISSUE }) {
            authorizationService.require(
                principal = principal,
                action = AuthorizationAction.TASK_READ,
                resource = principal.taskResource()
            )
        }
        return ApiResponse(data = ticketDraftService.removeItems(principal, request.items))
    }

    @PostMapping("/current/clear")
    @Operation(summary = "Clear current ticket draft")
    @ApiOperationSupport(order = 3)
    fun clearCurrent(
        @AuthenticationPrincipal principal: SecruxPrincipal
    ): ApiResponse<*> {
        val draft = ticketDraftService.getOrCreateCurrent(principal)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_READ,
            resource = principal.ticketResource(projectId = draft.projectId)
        )
        return ApiResponse(data = ticketDraftService.clearCurrent(principal))
    }

    @PatchMapping("/current")
    @Operation(summary = "Update current ticket draft metadata")
    @ApiOperationSupport(order = 4)
    fun updateCurrent(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: TicketDraftUpdateRequest
    ): ApiResponse<*> {
        val draft = ticketDraftService.getOrCreateCurrent(principal)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_CREATE,
            resource = principal.ticketResource(projectId = draft.projectId)
        )
        return ApiResponse(data = ticketDraftService.updateCurrent(principal, request))
    }

    @PostMapping("/current/ai-generate")
    @Operation(summary = "Generate ticket copy with AI")
    @ApiOperationSupport(order = 5)
    fun aiGenerate(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestBody(required = false) request: TicketDraftAiRequest?
    ): ApiResponse<*> {
        val draft = ticketDraftService.getOrCreateCurrent(principal)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_CREATE,
            resource = principal.ticketResource(projectId = draft.projectId)
        )
        return ApiResponse(data = ticketDraftService.triggerAiGenerate(principal, request ?: TicketDraftAiRequest()))
    }

    @PostMapping("/current/ai-polish")
    @Operation(summary = "Polish ticket copy with AI")
    @ApiOperationSupport(order = 6)
    fun aiPolish(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestBody(required = false) request: TicketDraftAiRequest?
    ): ApiResponse<*> {
        val draft = ticketDraftService.getOrCreateCurrent(principal)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_CREATE,
            resource = principal.ticketResource(projectId = draft.projectId)
        )
        return ApiResponse(data = ticketDraftService.triggerAiPolish(principal, request ?: TicketDraftAiRequest()))
    }

    @PostMapping("/current/ai-apply")
    @Operation(summary = "Apply AI job result to current draft")
    @ApiOperationSupport(order = 7)
    fun aiApply(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: TicketDraftAiApplyRequest
    ): ApiResponse<*> {
        val draft = ticketDraftService.getOrCreateCurrent(principal)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_CREATE,
            resource = principal.ticketResource(projectId = draft.projectId)
        )
        return ApiResponse(data = ticketDraftService.applyAiResult(principal, request))
    }
}
