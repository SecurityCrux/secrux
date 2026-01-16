package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class Project(
    val projectId: UUID,
    val tenantId: UUID,
    val name: String,
    val codeOwners: List<String>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?,
    val deletedAt: OffsetDateTime? = null
)

