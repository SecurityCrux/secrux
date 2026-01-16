package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

enum class TicketStatus { OPEN, SYNCED, FAILED }
enum class TicketDraftStatus { DRAFT, SUBMITTED, CLEARED }
enum class TicketIssueType { BUG, TASK, STORY }

data class Ticket(
    val ticketId: UUID,
    val tenantId: UUID,
    val projectId: UUID,
    val externalKey: String,
    val provider: String,
    val dedupeKey: String? = null,
    val payload: Map<String, Any?>,
    val status: TicketStatus,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class TicketDraft(
    val draftId: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val projectId: UUID?,
    val provider: String?,
    val titleI18n: Map<String, Any?>?,
    val descriptionI18n: Map<String, Any?>?,
    val status: TicketDraftStatus,
    val lastAiJobId: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class TicketProviderConfig(
    val tenantId: UUID,
    val provider: String,
    val baseUrl: String,
    val projectKey: String,
    val email: String,
    val apiTokenCipher: String,
    val issueTypeNames: Map<String, String>,
    val enabled: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

