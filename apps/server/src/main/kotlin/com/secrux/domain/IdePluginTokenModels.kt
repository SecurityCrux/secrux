package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class IdePluginToken(
    val tokenId: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val tokenHash: String,
    val tokenHint: String,
    val name: String?,
    val lastUsedAt: OffsetDateTime?,
    val revokedAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
)

