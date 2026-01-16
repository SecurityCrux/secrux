package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.TicketStatus
import com.secrux.dto.TicketSummary
import com.secrux.repo.TicketRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TicketQueryService(
    private val ticketRepository: TicketRepository
) {

    fun getTicketProjectId(tenantId: UUID, ticketId: UUID): UUID {
        val ticket = ticketRepository.findById(ticketId, tenantId)
            ?: throw SecruxException(ErrorCode.TICKET_NOT_FOUND, "Ticket not found")
        return ticket.projectId
    }

    fun getTicket(tenantId: UUID, ticketId: UUID): TicketSummary {
        val ticket = ticketRepository.findById(ticketId, tenantId)
            ?: throw SecruxException(ErrorCode.TICKET_NOT_FOUND, "Ticket not found")
        return ticket.toSummary()
    }

    fun listTickets(
        tenantId: UUID,
        projectId: UUID?,
        provider: String?,
        status: TicketStatus?,
        search: String?,
        limit: Int,
        offset: Int
    ): List<TicketSummary> =
        ticketRepository.listByTenant(
            tenantId = tenantId,
            projectId = projectId,
            provider = provider,
            status = status,
            search = search,
            limit = limit,
            offset = offset
        ).map { it.toSummary() }
}
