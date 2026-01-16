package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.AppUser
import com.secrux.domain.IamUserRole
import com.secrux.dto.UserCreateRequest
import com.secrux.dto.UserDetailResponse
import com.secrux.dto.UserListResponse
import com.secrux.dto.UserPasswordResetRequest
import com.secrux.dto.UserProfileUpdateRequest
import com.secrux.dto.UserRoleAssignmentResponse
import com.secrux.dto.UserStatusUpdateRequest
import com.secrux.dto.UserSummary
import com.secrux.repo.AppUserDirectoryRepository
import com.secrux.repo.IamPermissionRepository
import com.secrux.repo.IamRoleRepository
import com.secrux.repo.IamUserRoleRepository
import com.secrux.repo.LocalCredentialRepository
import com.secrux.security.AuthMode
import com.secrux.security.SecruxAuthProperties
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class IamUserAdminService(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val authProperties: SecruxAuthProperties,
    private val passwordHashService: PasswordHashService,
    private val userDirectoryRepository: AppUserDirectoryRepository,
    private val localCredentialRepository: LocalCredentialRepository,
    private val keycloakAdminService: KeycloakAdminService,
    private val roleRepository: IamRoleRepository,
    private val userRoleRepository: IamUserRoleRepository,
    private val permissionRepository: IamPermissionRepository,
) {

    fun listUsers(
        tenantId: UUID,
        search: String?,
        limit: Int,
        offset: Int
    ): UserListResponse {
        val safeLimit = limit.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        val (items, total) = userDirectoryRepository.list(tenantId, search, safeLimit, safeOffset)
        return UserListResponse(
            items =
                items.map { user ->
                    UserSummary(
                        id = user.userId.toString(),
                        username = user.username ?: user.email,
                        email = user.email,
                        enabled = user.enabled
                    )
                },
            total = total,
            limit = safeLimit,
            offset = safeOffset
        )
    }

    fun getUser(tenantId: UUID, userId: UUID): UserDetailResponse {
        val user = userDirectoryRepository.findById(tenantId, userId) ?: throw SecruxException(ErrorCode.USER_NOT_FOUND, "User not found")
        val roleIds = userRoleRepository.listRoleIdsByUser(tenantId, userId)
        val perms = permissionRepository.listEffectivePermissions(tenantId, userId).sorted()
        return user.toDetail(roleIds = roleIds, permissions = perms)
    }

    fun createLocalUser(tenantId: UUID, request: UserCreateRequest): UserSummary {
        return when (authProperties.mode) {
            AuthMode.KEYCLOAK -> createKeycloakUser(tenantId, request)
            AuthMode.LOCAL -> createLocalUserInternal(tenantId, request)
        }
    }

    private fun createLocalUserInternal(tenantId: UUID, request: UserCreateRequest): UserSummary {
        val username = request.username.trim()
        if (username.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "username is required")
        }
        val email =
            request.email
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "$username@$tenantId"

        val rawPassword = request.password?.takeIf { it.isNotBlank() }
            ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "password is required for local users")

        val now = OffsetDateTime.now(clock)
        val userId = UUID.randomUUID()
        val user =
            AppUser(
                userId = userId,
                tenantId = tenantId,
                username = username,
                email = email,
                phone = null,
                name = username,
                enabled = request.enabled,
                roles = emptyList(),
                lastLoginAt = null,
                createdAt = now,
                updatedAt = now
            )

        try {
            userDirectoryRepository.insert(user)
        } catch (ex: IntegrityConstraintViolationException) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "User already exists", cause = ex)
        }

        localCredentialRepository.upsert(
            userId = userId,
            tenantId = tenantId,
            passwordHash = passwordHashService.hash(rawPassword),
            mustChangePassword = false
        )

        return UserSummary(
            id = userId.toString(),
            username = username,
            email = email,
            enabled = request.enabled
        )
    }

    private fun createKeycloakUser(tenantId: UUID, request: UserCreateRequest): UserSummary {
        val username = request.username.trim()
        if (username.isBlank()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "username is required")
        }

        val rawPassword = request.password?.trim()?.takeIf { it.isNotBlank() }
        val defaultEmail =
            request.email
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "$username@$tenantId.secrux.local"

        val keycloakUser =
            keycloakAdminService.findByUsername(username)
                ?.also { existing ->
                    keycloakAdminService.ensureRequiredProfile(existing.id, fallbackName = username, fallbackEmail = defaultEmail)
                    keycloakAdminService.upsertTenantIdAttribute(existing.id, tenantId = tenantId, fallbackName = username, fallbackEmail = defaultEmail)
                    if (rawPassword != null) {
                        keycloakAdminService.resetPassword(existing.id, UserPasswordResetRequest(rawPassword, temporary = false))
                    } else {
                        keycloakAdminService.clearRequiredActions(existing.id)
                    }
                }
                ?: run {
                    val requiredPassword = rawPassword
                        ?: throw SecruxException(ErrorCode.VALIDATION_ERROR, "password is required for Keycloak users")
                    val created =
                        keycloakAdminService.createUser(
                        tenantId = tenantId,
                        request =
                            request.copy(
                                username = username,
                                email = defaultEmail,
                                password = requiredPassword,
                            )
                    )
                    keycloakAdminService.upsertTenantIdAttribute(created.id, tenantId = tenantId, fallbackName = username, fallbackEmail = defaultEmail)
                    created
                }

        val keycloakUserId =
            runCatching { UUID.fromString(keycloakUser.id) }.getOrElse { ex ->
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "Invalid Keycloak user id format: ${ex.message}", cause = ex)
            }

        val email = keycloakUser.email?.takeIf { it.isNotBlank() }
            ?: request.email?.trim()?.takeIf { it.isNotBlank() }
            ?: "$username@$tenantId.secrux.local"

        val existing = userDirectoryRepository.findByUsername(tenantId, username)
        val now = OffsetDateTime.now(clock)
        if (existing == null) {
            val user =
                AppUser(
                    userId = keycloakUserId,
                    tenantId = tenantId,
                    username = username,
                    email = email,
                    phone = null,
                    name = username,
                    enabled = keycloakUser.enabled,
                    roles = emptyList(),
                    lastLoginAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            try {
                userDirectoryRepository.insert(user)
            } catch (ex: IntegrityConstraintViolationException) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "User already exists", cause = ex)
            }
            return UserSummary(
                id = keycloakUserId.toString(),
                username = username,
                email = keycloakUser.email,
                enabled = keycloakUser.enabled
            )
        }

        if (existing.userId == keycloakUserId) {
            userDirectoryRepository.updateProfile(
                tenantId = tenantId,
                userId = existing.userId,
                username = username,
                email = email,
                phone = existing.phone,
                name = existing.name
            )
            userDirectoryRepository.updateStatus(tenantId, existing.userId, keycloakUser.enabled)
            return UserSummary(
                id = existing.userId.toString(),
                username = username,
                email = keycloakUser.email,
                enabled = keycloakUser.enabled
            )
        }

        migrateUserId(
            tenantId = tenantId,
            fromUserId = existing.userId,
            toUserId = keycloakUserId,
            username = username,
            email = email,
            enabled = keycloakUser.enabled
        )
        return UserSummary(
            id = keycloakUserId.toString(),
            username = username,
            email = keycloakUser.email,
            enabled = keycloakUser.enabled
        )
    }

    fun updateProfile(tenantId: UUID, userId: UUID, request: UserProfileUpdateRequest): UserDetailResponse {
        if (authProperties.mode == AuthMode.KEYCLOAK) {
            val fallbackUsername =
                request.username
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: userId.toString()
            val fallbackName =
                request.name
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: fallbackUsername
            val fallbackEmail =
                request.email
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "$fallbackUsername@$tenantId.secrux.local"
            keycloakAdminService.upsertTenantIdAttribute(userId.toString(), tenantId = tenantId, fallbackName = fallbackName, fallbackEmail = fallbackEmail)
            keycloakAdminService.updateProfile(userId.toString(), request)
        }
        val existing = userDirectoryRepository.findById(tenantId, userId) ?: throw SecruxException(ErrorCode.USER_NOT_FOUND, "User not found")
        val updated =
            try {
                userDirectoryRepository.updateProfile(
                    tenantId = tenantId,
                    userId = userId,
                    username = request.username?.trim()?.takeIf { it.isNotBlank() } ?: existing.username,
                    email = request.email?.trim()?.takeIf { it.isNotBlank() } ?: existing.email,
                    phone = request.phone?.trim()?.takeIf { it.isNotBlank() } ?: existing.phone,
                    name = request.name?.trim()?.takeIf { it.isNotBlank() } ?: existing.name
                )
            } catch (ex: IntegrityConstraintViolationException) {
                throw SecruxException(ErrorCode.VALIDATION_ERROR, "Profile conflicts with existing user", cause = ex)
            } ?: existing

        val roleIds = userRoleRepository.listRoleIdsByUser(tenantId, userId)
        val perms = permissionRepository.listEffectivePermissions(tenantId, userId).sorted()
        return updated.toDetail(roleIds = roleIds, permissions = perms)
    }

    fun updateStatus(tenantId: UUID, userId: UUID, request: UserStatusUpdateRequest) {
        if (authProperties.mode == AuthMode.KEYCLOAK) {
            keycloakAdminService.updateStatus(userId.toString(), request)
            userDirectoryRepository.updateStatus(tenantId, userId, request.enabled)
            return
        }
        val updated = userDirectoryRepository.updateStatus(tenantId, userId, request.enabled)
        if (!updated) {
            throw SecruxException(ErrorCode.USER_NOT_FOUND, "User not found")
        }
    }

    fun resetPassword(tenantId: UUID, userId: UUID, request: UserPasswordResetRequest) {
        if (authProperties.mode == AuthMode.KEYCLOAK) {
            val existing = userDirectoryRepository.findById(tenantId, userId)
            val fallbackUsername =
                existing
                    ?.username
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: userId.toString()
            val fallbackName =
                existing
                    ?.name
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: fallbackUsername
            val fallbackEmail = "$fallbackUsername@$tenantId.secrux.local"
            keycloakAdminService.ensureRequiredProfile(userId.toString(), fallbackName = fallbackName, fallbackEmail = fallbackEmail)
            keycloakAdminService.upsertTenantIdAttribute(userId.toString(), tenantId = tenantId, fallbackName = fallbackName, fallbackEmail = fallbackEmail)
            keycloakAdminService.resetPassword(userId.toString(), request.copy(temporary = false))
            return
        }
        userDirectoryRepository.findById(tenantId, userId) ?: throw SecruxException(ErrorCode.USER_NOT_FOUND, "User not found")
        localCredentialRepository.upsert(
            userId = userId,
            tenantId = tenantId,
            passwordHash = passwordHashService.hash(request.password),
            mustChangePassword = request.temporary
        )
    }

    fun assignRoles(tenantId: UUID, userId: UUID, roleIds: List<UUID>): UserRoleAssignmentResponse {
        userDirectoryRepository.findById(tenantId, userId) ?: throw SecruxException(ErrorCode.USER_NOT_FOUND, "User not found")
        val normalizedRoleIds = roleIds.distinct()
        if (normalizedRoleIds.isNotEmpty()) {
            val roles = roleRepository.listByIds(tenantId, normalizedRoleIds)
            if (roles.size != normalizedRoleIds.size) {
                throw SecruxException(ErrorCode.ROLE_NOT_FOUND, "One or more roles not found")
            }
        }

        val now = OffsetDateTime.now(clock)
        val assignments =
            normalizedRoleIds.map { roleId ->
                IamUserRole(
                    id = UUID.randomUUID(),
                    tenantId = tenantId,
                    userId = userId,
                    roleId = roleId,
                    createdAt = now
                )
            }

        dsl.transaction { config ->
            val ctx = DSL.using(config)
            userRoleRepository.deleteByUser(ctx, tenantId, userId)
            userRoleRepository.insertAll(ctx, assignments)
        }

        val perms = permissionRepository.listEffectivePermissions(tenantId, userId).sorted()
        userDirectoryRepository.setLegacyRoles(tenantId, userId, perms)

        return UserRoleAssignmentResponse(
            userId = userId,
            roleIds = normalizedRoleIds,
            permissions = perms
        )
    }

    private fun migrateUserId(
        tenantId: UUID,
        fromUserId: UUID,
        toUserId: UUID,
        username: String,
        email: String,
        enabled: Boolean
    ) {
        val now = OffsetDateTime.now(clock)
        val tenantIdField = DSL.field("tenant_id", UUID::class.java)
        val userIdField = DSL.field("user_id", UUID::class.java)

        dsl.transaction { config ->
            val ctx = DSL.using(config)

            val iamUserRoleTable = DSL.table("iam_user_role")
            val roleIdField = DSL.field("role_id", UUID::class.java)
            ctx.deleteFrom(iamUserRoleTable)
                .where(tenantIdField.eq(tenantId))
                .and(userIdField.eq(fromUserId))
                .and(
                    roleIdField.`in`(
                        ctx.select(roleIdField)
                            .from(iamUserRoleTable)
                            .where(tenantIdField.eq(tenantId))
                            .and(userIdField.eq(toUserId))
                    )
                ).execute()
            ctx.update(iamUserRoleTable)
                .set(userIdField, toUserId)
                .where(tenantIdField.eq(tenantId))
                .and(userIdField.eq(fromUserId))
                .execute()

            val ticketDraftTable = DSL.table("ticket_draft")
            val draftStatusField = DSL.field("status", String::class.java)
            val draftDeletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
            val hasTargetDraft =
                ctx.fetchExists(
                    DSL.selectOne()
                        .from(ticketDraftTable)
                        .where(tenantIdField.eq(tenantId))
                        .and(userIdField.eq(toUserId))
                        .and(draftStatusField.eq("DRAFT"))
                        .and(draftDeletedAtField.isNull)
                )
            if (hasTargetDraft) {
                ctx.update(ticketDraftTable)
                    .set(draftDeletedAtField, now)
                    .where(tenantIdField.eq(tenantId))
                    .and(userIdField.eq(fromUserId))
                    .and(draftStatusField.eq("DRAFT"))
                    .and(draftDeletedAtField.isNull)
                    .execute()
            }
            ctx.update(ticketDraftTable)
                .set(userIdField, toUserId)
                .where(tenantIdField.eq(tenantId))
                .and(userIdField.eq(fromUserId))
                .execute()

            val taskTable = DSL.table("task")
            val ownerField = DSL.field("owner", UUID::class.java)
            ctx.update(taskTable)
                .set(ownerField, toUserId)
                .where(tenantIdField.eq(tenantId))
                .and(ownerField.eq(fromUserId))
                .execute()

            ctx.deleteFrom(DSL.table("iam_refresh_token"))
                .where(tenantIdField.eq(tenantId))
                .and(userIdField.eq(fromUserId))
                .execute()
            ctx.deleteFrom(DSL.table("iam_local_credential"))
                .where(tenantIdField.eq(tenantId))
                .and(userIdField.eq(fromUserId))
                .execute()

            val appUserTable = DSL.table("app_user")
            val appUserIdField = DSL.field("user_id", UUID::class.java)
            val appUsernameField = DSL.field("username", String::class.java)
            val appEmailField = DSL.field("email", String::class.java)
            val appEnabledField = DSL.field("enabled", Boolean::class.javaObjectType)
            val appUpdatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)
            val targetExists = ctx.fetchExists(
                DSL.selectOne()
                    .from(appUserTable)
                    .where(appUserIdField.eq(toUserId))
                    .and(tenantIdField.eq(tenantId))
            )
            if (targetExists) {
                ctx.update(appUserTable)
                    .set(appUsernameField, username)
                    .set(appEmailField, email)
                    .set(appEnabledField, enabled)
                    .set(appUpdatedAtField, now)
                    .where(appUserIdField.eq(toUserId))
                    .and(tenantIdField.eq(tenantId))
                    .execute()
                ctx.deleteFrom(appUserTable)
                    .where(appUserIdField.eq(fromUserId))
                    .and(tenantIdField.eq(tenantId))
                    .execute()
            } else {
                ctx.update(appUserTable)
                    .set(appUserIdField, toUserId)
                    .set(appUsernameField, username)
                    .set(appEmailField, email)
                    .set(appEnabledField, enabled)
                    .set(appUpdatedAtField, now)
                    .where(appUserIdField.eq(fromUserId))
                    .and(tenantIdField.eq(tenantId))
                    .execute()
            }
        }
    }

    private fun AppUser.toDetail(roleIds: List<UUID>, permissions: List<String>): UserDetailResponse =
        UserDetailResponse(
            userId = userId,
            tenantId = tenantId,
            username = username,
            email = email,
            phone = phone,
            name = name,
            enabled = enabled,
            roleIds = roleIds,
            permissions = permissions,
            lastLoginAt = lastLoginAt,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
}
