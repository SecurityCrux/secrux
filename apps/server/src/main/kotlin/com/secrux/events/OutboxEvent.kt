package com.secrux.events

import java.time.OffsetDateTime
import java.util.UUID

enum class OutboxStatus { PENDING, PROCESSED }

data class OutboxEvent(
    val eventId: UUID,
    val tenantId: UUID?,
    val correlationId: String,
    val eventType: String,
    val payload: Map<String, Any?>,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val createdAt: OffsetDateTime,
    val processedAt: OffsetDateTime? = null
)
