package com.secrux.service

import com.secrux.domain.TicketDraft
import com.secrux.domain.TicketDraftStatus
import com.secrux.repo.TicketDraftRepository
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class TicketDraftCurrentService(
    private val ticketDraftRepository: TicketDraftRepository,
    private val clock: Clock
) {

    fun getOrCreate(principal: SecruxPrincipal): TicketDraft {
        val existing = ticketDraftRepository.findCurrent(principal.tenantId, principal.userId)
        if (existing != null) return existing

        val draft =
            TicketDraft(
                draftId = UUID.randomUUID(),
                tenantId = principal.tenantId,
                userId = principal.userId,
                projectId = null,
                provider = null,
                titleI18n = null,
                descriptionI18n = null,
                status = TicketDraftStatus.DRAFT,
                lastAiJobId = null,
                createdAt = OffsetDateTime.now(clock),
                updatedAt = null
            )
        ticketDraftRepository.insert(draft)
        return draft
    }
}

