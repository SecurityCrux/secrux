package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.IamRole
import com.secrux.domain.IamRolePermission
import com.secrux.dto.IamRoleCreateRequest
import com.secrux.dto.IamRoleDetailResponse
import com.secrux.dto.IamRolePermissionsUpdateRequest
import com.secrux.dto.IamRoleSummaryResponse
import com.secrux.dto.IamRoleUpdateRequest
import com.secrux.dto.PageResponse
import com.secrux.repo.IamRolePermissionRepository
import com.secrux.repo.IamRoleRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class IamRoleService(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val permissionCatalogService: IamPermissionCatalogService,
    private val bootstrapService: IamBootstrapService,
    private val roleRepository: IamRoleRepository,
    private val rolePermissionRepository: IamRolePermissionRepository
) {

    fun listRoles(
        tenantId: UUID,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<IamRoleSummaryResponse> {
        bootstrapService.ensureBuiltInRoles(tenantId)
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        val (roles, total) = roleRepository.list(tenantId, search, safeLimit, safeOffset)
        return PageResponse(
            items = roles.map { it.toSummary() },
            total = total,
            limit = safeLimit,
            offset = safeOffset
        )
    }

    fun createRole(tenantId: UUID, request: IamRoleCreateRequest): IamRoleDetailResponse {
        bootstrapService.ensureBuiltInRoles(tenantId)
        val key = request.key.trim()
        if (!ROLE_KEY_REGEX.matches(key)) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Invalid role key format")
        }
        if (roleRepository.findByKey(tenantId, key) != null) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Role key already exists")
        }

        val now = OffsetDateTime.now(clock)
        val role =
            IamRole(
                roleId = UUID.randomUUID(),
                tenantId = tenantId,
                key = key,
                name = request.name.trim(),
                description = request.description?.trim()?.takeIf { it.isNotBlank() },
                builtIn = false,
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            )

        val permissions = normalizePermissions(request.permissions)
        val permRows =
            permissions.map { perm ->
                IamRolePermission(
                    id = UUID.randomUUID(),
                    tenantId = tenantId,
                    roleId = role.roleId,
                    permission = perm,
                    createdAt = now
                )
            }

        dsl.transaction { config ->
            val ctx = DSL.using(config)
            roleRepository.insert(ctx, role)
            rolePermissionRepository.insertAll(ctx, permRows)
        }

        return IamRoleDetailResponse(
            roleId = role.roleId,
            key = role.key,
            name = role.name,
            description = role.description,
            builtIn = role.builtIn,
            permissions = permissions.sorted(),
            createdAt = role.createdAt,
            updatedAt = role.updatedAt
        )
    }

    fun getRole(tenantId: UUID, roleId: UUID): IamRoleDetailResponse {
        val role = roleRepository.findById(tenantId, roleId) ?: throw SecruxException(ErrorCode.ROLE_NOT_FOUND, "Role not found")
        val permissions = rolePermissionRepository.listPermissions(tenantId, roleId)
        return IamRoleDetailResponse(
            roleId = role.roleId,
            key = role.key,
            name = role.name,
            description = role.description,
            builtIn = role.builtIn,
            permissions = permissions.sorted(),
            createdAt = role.createdAt,
            updatedAt = role.updatedAt
        )
    }

    fun updateRole(tenantId: UUID, roleId: UUID, request: IamRoleUpdateRequest): IamRoleSummaryResponse {
        val existing = roleRepository.findById(tenantId, roleId) ?: throw SecruxException(ErrorCode.ROLE_NOT_FOUND, "Role not found")
        val updated =
            roleRepository.updateMetadata(
                tenantId = tenantId,
                roleId = roleId,
                name = request.name.trim(),
                description = request.description?.trim()?.takeIf { it.isNotBlank() }
            ) ?: existing
        return updated.toSummary()
    }

    fun deleteRole(tenantId: UUID, roleId: UUID) {
        val role = roleRepository.findById(tenantId, roleId) ?: throw SecruxException(ErrorCode.ROLE_NOT_FOUND, "Role not found")
        if (role.builtIn) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Built-in roles cannot be deleted")
        }
        roleRepository.softDelete(tenantId, roleId)
    }

    fun setRolePermissions(tenantId: UUID, roleId: UUID, request: IamRolePermissionsUpdateRequest): List<String> {
        val role = roleRepository.findById(tenantId, roleId) ?: throw SecruxException(ErrorCode.ROLE_NOT_FOUND, "Role not found")
        val permissions = normalizePermissions(request.permissions)
        val now = OffsetDateTime.now(clock)
        val rows =
            permissions.map { perm ->
                IamRolePermission(
                    id = UUID.randomUUID(),
                    tenantId = tenantId,
                    roleId = role.roleId,
                    permission = perm,
                    createdAt = now
                )
            }
        dsl.transaction { config ->
            val ctx = DSL.using(config)
            rolePermissionRepository.deleteByRole(ctx, tenantId, roleId)
            rolePermissionRepository.insertAll(ctx, rows)
        }
        return permissions.sorted()
    }

    private fun normalizePermissions(permissions: List<String>): List<String> {
        val allowed = permissionCatalogService.catalog().permissions.toSet()
        val normalized =
            permissions
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        val unknown = normalized.filterNot { allowed.contains(it) }
        if (unknown.isNotEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Unknown permissions: ${unknown.sorted().joinToString(",")}")
        }
        return normalized
    }

    private fun IamRole.toSummary(): IamRoleSummaryResponse =
        IamRoleSummaryResponse(
            roleId = roleId,
            key = key,
            name = name,
            description = description,
            builtIn = builtIn,
            createdAt = createdAt,
            updatedAt = updatedAt
        )

    companion object {
        private val ROLE_KEY_REGEX = Regex("^[a-zA-Z0-9:_-]{2,64}$")
    }
}

