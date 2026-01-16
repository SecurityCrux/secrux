package com.secrux.service

import com.secrux.domain.Ticket
import com.secrux.dto.TicketSummary

internal fun Ticket.toSummary(): TicketSummary =
    TicketSummary(
        ticketId = ticketId,
        projectId = projectId,
        provider = provider,
        externalKey = externalKey,
        status = status,
        payload = payload,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt?.toString()
    )

