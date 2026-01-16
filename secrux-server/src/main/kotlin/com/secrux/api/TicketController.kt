package com.secrux.api

import com.github.xiaoymin.knife4j.annotations.ApiOperationSupport
import com.secrux.common.ApiResponse
import com.secrux.domain.TicketStatus
import com.secrux.dto.TicketCreationRequest
import com.secrux.dto.TicketCreateFromDraftRequest
import com.secrux.dto.TicketStatusUpdateRequest
import com.secrux.service.TicketDraftService
import com.secrux.service.TicketService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import com.secrux.security.AuthorizationAction
import com.secrux.security.AuthorizationService
import com.secrux.security.SecruxPrincipal
import com.secrux.security.ticketResource
import java.util.UUID

@RestController
@RequestMapping("/tickets")
@Tag(name = "Ticket APIs", description = "Ticket automation endpoints")
class TicketController(
    private val ticketService: TicketService,
    private val ticketDraftService: TicketDraftService,
    private val authorizationService: AuthorizationService
) {

    @GetMapping
    @Operation(summary = "List tickets", description = "List tickets for tenant")
    @ApiOperationSupport(order = 0)
    fun listTickets(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) status: TicketStatus?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_READ,
            resource = principal.ticketResource(projectId = projectId)
        )
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        return ApiResponse(
            data = ticketService.listTickets(
                tenantId = principal.tenantId,
                projectId = projectId,
                provider = provider,
                status = status,
                search = search,
                limit = safeLimit,
                offset = safeOffset
            )
        )
    }

    @GetMapping("/{ticketId}")
    @Operation(summary = "Get ticket", description = "Fetch a ticket by ID")
    @ApiOperationSupport(order = 1)
    fun getTicket(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable ticketId: UUID
    ): ApiResponse<*> {
        val ticket = ticketService.getTicket(principal.tenantId, ticketId)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_READ,
            resource = principal.ticketResource(projectId = ticket.projectId)
        )
        return ApiResponse(data = ticket)
    }

    @PostMapping
    @Operation(summary = "Create tickets", description = "Create external tickets based on findings")
    @ApiOperationSupport(order = 2)
    fun createTickets(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: TicketCreationRequest
    ): ApiResponse<*> {
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_CREATE,
            resource = principal.ticketResource(
                projectId = request.projectId,
                attributes = mapOf(
                    "findingCount" to request.findingIds.size
                )
            )
        )
        return ApiResponse(data = ticketService.createTickets(principal.tenantId, request))
    }

    @PostMapping("/from-draft")
    @Operation(summary = "Create ticket from current draft", description = "Create an external ticket based on the current user's draft basket")
    @ApiOperationSupport(order = 3)
    fun createFromDraft(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @Valid @RequestBody request: TicketCreateFromDraftRequest
    ): ApiResponse<*> {
        val draft = ticketDraftService.getOrCreateCurrent(principal)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_CREATE,
            resource = principal.ticketResource(projectId = draft.projectId)
        )
        return ApiResponse(data = ticketService.createFromCurrentDraft(principal, request))
    }

    @PatchMapping("/{ticketId}")
    @Operation(summary = "Update ticket status")
    @ApiOperationSupport(order = 4)
    fun updateTicketStatus(
        @AuthenticationPrincipal principal: SecruxPrincipal,
        @PathVariable ticketId: UUID,
        @Valid @RequestBody request: TicketStatusUpdateRequest
    ): ApiResponse<*> {
        val projectId = ticketService.getTicketProjectId(principal.tenantId, ticketId)
        authorizationService.require(
            principal = principal,
            action = AuthorizationAction.TICKET_MANAGE,
            resource = principal.ticketResource(projectId = projectId)
        )
        return ApiResponse(data = ticketService.updateStatus(principal.tenantId, ticketId, request))
    }
}
