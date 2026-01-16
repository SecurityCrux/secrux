package com.secrux.security

import java.util.UUID

data class AuthorizationResource(
    val tenantId: UUID,
    val type: String,
    val resourceId: UUID? = null,
    val projectId: UUID? = null,
    val taskId: UUID? = null,
    val attributes: Map<String, Any?> = emptyMap()
)

