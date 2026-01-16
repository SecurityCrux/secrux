package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class Tenant(
    val tenantId: UUID,
    val name: String,
    val plan: String,
    val contactEmail: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

