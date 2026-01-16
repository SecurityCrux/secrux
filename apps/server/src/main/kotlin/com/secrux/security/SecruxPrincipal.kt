package com.secrux.security

import java.util.UUID

data class SecruxPrincipal(
    val tenantId: UUID,
    val userId: UUID,
    val email: String?,
    val username: String?,
    val roles: Set<String>
)

