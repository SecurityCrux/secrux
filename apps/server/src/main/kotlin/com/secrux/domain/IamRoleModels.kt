package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class IamRole(
    val roleId: UUID,
    val tenantId: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val builtIn: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?,
    val deletedAt: OffsetDateTime?
)

data class IamRolePermission(
    val id: UUID,
    val tenantId: UUID,
    val roleId: UUID,
    val permission: String,
    val createdAt: OffsetDateTime
)

data class IamUserRole(
    val id: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val roleId: UUID,
    val createdAt: OffsetDateTime
)

