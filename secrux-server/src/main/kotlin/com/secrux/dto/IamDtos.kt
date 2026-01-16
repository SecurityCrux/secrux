package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.OffsetDateTime
import java.util.UUID

data class PermissionGroup(
    val group: String,
    val permissions: List<String>
)

data class PermissionCatalogResponse(
    val permissions: List<String>,
    val groups: List<PermissionGroup>
)

@Schema(name = "IamRoleCreateRequest")
data class IamRoleCreateRequest(
    @field:NotBlank val key: String,
    @field:NotBlank val name: String,
    val description: String? = null,
    val permissions: List<String> = emptyList()
)

@Schema(name = "IamRoleUpdateRequest")
data class IamRoleUpdateRequest(
    @field:NotBlank val name: String,
    val description: String? = null
)

data class IamRoleSummaryResponse(
    val roleId: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val builtIn: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class IamRoleDetailResponse(
    val roleId: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val builtIn: Boolean,
    val permissions: List<String>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class IamRolePermissionsUpdateRequest(
    val permissions: List<String> = emptyList()
)

@Schema(name = "UserProfileUpdateRequest")
data class UserProfileUpdateRequest(
    val username: String? = null,
    @field:Email val email: String? = null,
    val phone: String? = null,
    val name: String? = null
)

data class UserRoleAssignmentRequest(
    val roleIds: List<UUID> = emptyList()
)

data class UserRoleAssignmentResponse(
    val userId: UUID,
    val roleIds: List<UUID>,
    val permissions: List<String>
)

data class UserDetailResponse(
    val userId: UUID,
    val tenantId: UUID,
    val username: String?,
    val email: String,
    val phone: String?,
    val name: String?,
    val enabled: Boolean,
    val roleIds: List<UUID>,
    val permissions: List<String>,
    val lastLoginAt: OffsetDateTime?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

@Schema(name = "TenantUpdateRequest")
data class TenantUpdateRequest(
    @field:NotBlank val name: String,
    @field:Email val contactEmail: String? = null
)

data class TenantResponse(
    val tenantId: UUID,
    val name: String,
    val plan: String,
    val contactEmail: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

