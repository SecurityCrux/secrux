package com.secrux.service

import com.secrux.domain.IamRole
import com.secrux.domain.IamRolePermission
import com.secrux.repo.IamRolePermissionRepository
import com.secrux.repo.IamRoleRepository
import com.secrux.security.AuthorizationAction
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class IamBootstrapService(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val permissionCatalogService: IamPermissionCatalogService,
    private val roleRepository: IamRoleRepository,
    private val rolePermissionRepository: IamRolePermissionRepository
) {

    fun ensureBuiltInRoles(tenantId: UUID): BuiltInRoles {
        val allPermissions = permissionCatalogService.catalog().permissions

        val adminRole =
            ensureBuiltInRole(
                tenantId = tenantId,
                key = ADMIN_ROLE_KEY,
                name = "Admin",
                description = "Built-in role with full permissions",
                initialPermissions = allPermissions,
                keepSyncedWithCatalog = true
            )

        val viewerRole =
            ensureBuiltInRole(
                tenantId = tenantId,
                key = VIEWER_ROLE_KEY,
                name = "Viewer",
                description = "Built-in read-only role",
                initialPermissions = viewerPermissions(allPermissions),
                keepSyncedWithCatalog = false
            )

        val operatorRole =
            ensureBuiltInRole(
                tenantId = tenantId,
                key = OPERATOR_ROLE_KEY,
                name = "Operator",
                description = "Built-in role for running scans and managing tasks/projects",
                initialPermissions = operatorPermissions(),
                keepSyncedWithCatalog = false
            )

        val securityManagerRole =
            ensureBuiltInRole(
                tenantId = tenantId,
                key = SECURITY_MANAGER_ROLE_KEY,
                name = "Security Manager",
                description = "Built-in role for managing findings, baselines, tickets, and rules",
                initialPermissions = securityManagerPermissions(),
                keepSyncedWithCatalog = false
            )

        val userManagerRole =
            ensureBuiltInRole(
                tenantId = tenantId,
                key = USER_MANAGER_ROLE_KEY,
                name = "User Manager",
                description = "Built-in role for managing users and role groups",
                initialPermissions = userManagerPermissions(),
                keepSyncedWithCatalog = false
            )

        return BuiltInRoles(
            adminRoleId = adminRole.roleId,
            viewerRoleId = viewerRole.roleId,
            operatorRoleId = operatorRole.roleId,
            securityManagerRoleId = securityManagerRole.roleId,
            userManagerRoleId = userManagerRole.roleId,
        )
    }

    private fun ensureBuiltInRole(
        tenantId: UUID,
        key: String,
        name: String,
        description: String,
        initialPermissions: List<String>,
        keepSyncedWithCatalog: Boolean,
    ): IamRole {
        val existing = roleRepository.findByKey(tenantId, key)
        if (existing != null) {
            if (keepSyncedWithCatalog) {
                ensureRolePermissions(tenantId, existing.roleId, permissionCatalogService.catalog().permissions)
            }
            return existing
        }

        val now = OffsetDateTime.now(clock)
        val role =
            IamRole(
                roleId = UUID.randomUUID(),
                tenantId = tenantId,
                key = key,
                name = name,
                description = description,
                builtIn = true,
                createdAt = now,
                updatedAt = now,
                deletedAt = null
            )

        dsl.transaction { config ->
            val ctx = DSL.using(config)
            roleRepository.insert(ctx, role)
            rolePermissionRepository.insertAll(ctx, buildPermissionRows(now, tenantId, role.roleId, initialPermissions))
        }

        if (keepSyncedWithCatalog) {
            ensureRolePermissions(tenantId, role.roleId, permissionCatalogService.catalog().permissions)
        }
        return role
    }

    private fun ensureRolePermissions(tenantId: UUID, roleId: UUID, desired: List<String>) {
        val existing = rolePermissionRepository.listPermissions(tenantId, roleId).toSet()
        val missing = desired.filter { it.isNotBlank() && !existing.contains(it) }
        if (missing.isEmpty()) return

        val now = OffsetDateTime.now(clock)
        val items = buildPermissionRows(now, tenantId, roleId, missing)
        dsl.transaction { config ->
            val ctx = DSL.using(config)
            rolePermissionRepository.insertAll(ctx, items)
        }
    }

    private fun buildPermissionRows(
        now: OffsetDateTime,
        tenantId: UUID,
        roleId: UUID,
        permissions: List<String>,
    ): List<IamRolePermission> =
        permissions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { permission ->
                IamRolePermission(
                    id = UUID.randomUUID(),
                    tenantId = tenantId,
                    roleId = roleId,
                    permission = permission,
                    createdAt = now
                )
            }

    private fun viewerPermissions(allPermissions: List<String>): List<String> =
        allPermissions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { it.endsWith(":read") }

    private fun userManagerPermissions(): List<String> =
        listOf(
            AuthorizationAction.USER_READ.value,
            AuthorizationAction.USER_MANAGE.value,
        )

    private fun operatorPermissions(): List<String> =
        listOf(
            AuthorizationAction.PROJECT_CREATE.value,
            AuthorizationAction.PROJECT_UPDATE.value,
            AuthorizationAction.PROJECT_READ.value,
            AuthorizationAction.REPOSITORY_MANAGE.value,
            AuthorizationAction.REPOSITORY_READ.value,
            AuthorizationAction.TASK_CREATE.value,
            AuthorizationAction.TASK_READ.value,
            AuthorizationAction.TASK_UPDATE.value,
            AuthorizationAction.TASK_DELETE.value,
            AuthorizationAction.TASK_STAGE_READ.value,
            AuthorizationAction.TASK_ARTIFACT_READ.value,
            AuthorizationAction.STAGE_READ.value,
            AuthorizationAction.STAGE_EXECUTE.value,
            AuthorizationAction.FINDING_READ.value,
            AuthorizationAction.BASELINE_READ.value,
            AuthorizationAction.TICKET_CREATE.value,
            AuthorizationAction.TICKET_READ.value,
            AuthorizationAction.EXECUTOR_READ.value,
            AuthorizationAction.RULE_READ.value,
        )

    private fun securityManagerPermissions(): List<String> =
        listOf(
            AuthorizationAction.TASK_READ.value,
            AuthorizationAction.TASK_STAGE_READ.value,
            AuthorizationAction.TASK_ARTIFACT_READ.value,
            AuthorizationAction.STAGE_READ.value,
            AuthorizationAction.PROJECT_READ.value,
            AuthorizationAction.REPOSITORY_READ.value,
            AuthorizationAction.FINDING_READ.value,
            AuthorizationAction.FINDING_MANAGE.value,
            AuthorizationAction.BASELINE_READ.value,
            AuthorizationAction.BASELINE_MANAGE.value,
            AuthorizationAction.TICKET_READ.value,
            AuthorizationAction.TICKET_CREATE.value,
            AuthorizationAction.TICKET_MANAGE.value,
            AuthorizationAction.RULE_READ.value,
            AuthorizationAction.RULE_MANAGE.value,
            AuthorizationAction.RULESET_PUBLISH.value,
        )

    data class BuiltInRoles(
        val adminRoleId: UUID,
        val viewerRoleId: UUID,
        val operatorRoleId: UUID,
        val securityManagerRoleId: UUID,
        val userManagerRoleId: UUID,
    )

    companion object {
        private const val ADMIN_ROLE_KEY = "admin"
        private const val VIEWER_ROLE_KEY = "viewer"
        private const val OPERATOR_ROLE_KEY = "operator"
        private const val SECURITY_MANAGER_ROLE_KEY = "security_manager"
        private const val USER_MANAGER_ROLE_KEY = "user_manager"
    }
}
