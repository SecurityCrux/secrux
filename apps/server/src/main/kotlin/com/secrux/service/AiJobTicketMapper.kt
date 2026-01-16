package com.secrux.service

import com.secrux.ai.AiJobTicket
import com.secrux.dto.AiJobTicketResponse

internal fun AiJobTicket.toResponse(): AiJobTicketResponse =
    AiJobTicketResponse(
        jobId = jobId,
        status = status,
        jobType = jobType,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        result = result,
        error = error
    )

