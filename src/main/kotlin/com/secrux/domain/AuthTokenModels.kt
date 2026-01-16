package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class LocalCredential(
    val userId: UUID,
    val tenantId: UUID,
    val passwordHash: String,
    val mustChangePassword: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class RefreshToken(
    val tokenId: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val tokenHash: String,
    val expiresAt: OffsetDateTime,
    val revokedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime
)
