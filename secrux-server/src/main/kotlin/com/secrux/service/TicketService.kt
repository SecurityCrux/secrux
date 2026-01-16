package com.secrux.service

import com.secrux.domain.TicketStatus
import com.secrux.dto.TicketCreateFromDraftRequest
import com.secrux.dto.TicketCreationRequest
import com.secrux.dto.TicketResponse
import com.secrux.dto.TicketSummary
import com.secrux.dto.TicketStatusUpdateRequest
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TicketService(
    private val ticketQueryService: TicketQueryService,
    private val ticketCommandService: TicketCommandService
) {

    fun createTickets(tenantId: UUID, request: TicketCreationRequest): List<TicketResponse> =
        ticketCommandService.createTickets(tenantId, request)

    fun createFromCurrentDraft(
        principal: SecruxPrincipal,
        request: TicketCreateFromDraftRequest
    ): List<TicketResponse> = ticketCommandService.createFromCurrentDraft(principal, request)

    fun getTicketProjectId(tenantId: UUID, ticketId: UUID): UUID {
        return ticketQueryService.getTicketProjectId(tenantId, ticketId)
    }

    fun getTicket(tenantId: UUID, ticketId: UUID): TicketSummary {
        return ticketQueryService.getTicket(tenantId, ticketId)
    }

    fun listTickets(
        tenantId: UUID,
        projectId: UUID?,
        provider: String?,
        status: TicketStatus?,
        search: String?,
        limit: Int,
        offset: Int
    ): List<TicketSummary> = ticketQueryService.listTickets(tenantId, projectId, provider, status, search, limit, offset)

    fun updateStatus(
        tenantId: UUID,
        ticketId: UUID,
        request: TicketStatusUpdateRequest
    ): TicketSummary = ticketCommandService.updateStatus(tenantId, ticketId, request)
}
