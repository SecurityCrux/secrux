package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class AppUser(
    val userId: UUID,
    val tenantId: UUID,
    val username: String?,
    val email: String,
    val phone: String?,
    val name: String?,
    val enabled: Boolean,
    val roles: List<String>,
    val lastLoginAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

