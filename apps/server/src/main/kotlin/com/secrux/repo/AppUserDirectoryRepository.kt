package com.secrux.repo

import com.secrux.domain.AppUser
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class AppUserDirectoryRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {
    private val table = DSL.table("app_user")

    private val userIdField = DSL.field("user_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val usernameField = DSL.field("username", String::class.java)
    private val emailField = DSL.field("email", String::class.java)
    private val phoneField = DSL.field("phone", String::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val enabledField = DSL.field("enabled", Boolean::class.javaObjectType)
    private val rolesField = DSL.field("roles", Array<String>::class.java)
    private val lastLoginAtField = DSL.field("last_login_at", OffsetDateTime::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)

    fun findById(tenantId: UUID, userId: UUID): AppUser? =
        dsl.selectFrom(table)
            .where(userIdField.eq(userId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne { mapUser(it) }

    fun findTenantIdByUserId(userId: UUID): UUID? =
        dsl.select(tenantIdField)
            .from(table)
            .where(userIdField.eq(userId))
            .and(deletedAtField.isNull)
            .fetchOne(tenantIdField)

    fun findByUsernameOrEmail(
        tenantId: UUID?,
        usernameOrEmail: String
    ): List<AppUser> {
        val term = usernameOrEmail.trim()
        if (term.isBlank()) return emptyList()
        var condition =
            deletedAtField.isNull.and(
                DSL.lower(usernameField).eq(term.lowercase()).or(DSL.lower(emailField).eq(term.lowercase()))
            )
        tenantId?.let { condition = condition.and(tenantIdField.eq(it)) }
        return dsl.selectFrom(table)
            .where(condition)
            .orderBy(createdAtField.desc())
            .fetch { mapUser(it) }
    }

    fun findByUsername(
        tenantId: UUID,
        username: String
    ): AppUser? {
        val term = username.trim()
        if (term.isBlank()) return null
        return dsl.selectFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .and(DSL.lower(usernameField).eq(term.lowercase()))
            .fetchOne { mapUser(it) }
    }

    fun list(
        tenantId: UUID,
        search: String?,
        limit: Int,
        offset: Int
    ): Pair<List<AppUser>, Long> {
        var condition = tenantIdField.eq(tenantId).and(deletedAtField.isNull)
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val like = "%${term.lowercase()}%"
            condition =
                condition.and(
                    DSL.lower(usernameField).like(like)
                        .or(DSL.lower(emailField).like(like))
                        .or(DSL.lower(nameField).like(like))
                )
        }

        val total =
            dsl.selectCount()
                .from(table)
                .where(condition)
                .fetchOne(0, Long::class.java) ?: 0L

        val items =
            dsl.selectFrom(table)
                .where(condition)
                .orderBy(createdAtField.desc())
                .limit(limit)
                .offset(offset)
                .fetch { mapUser(it) }

        return items to total
    }

    fun insert(user: AppUser) {
        dsl.insertInto(table)
            .set(userIdField, user.userId)
            .set(tenantIdField, user.tenantId)
            .set(usernameField, user.username)
            .set(emailField, user.email)
            .set(phoneField, user.phone)
            .set(nameField, user.name)
            .set(enabledField, user.enabled)
            .set(rolesField, user.roles.toTypedArray())
            .set(lastLoginAtField, user.lastLoginAt)
            .set(createdAtField, user.createdAt)
            .set(updatedAtField, user.updatedAt)
            .execute()
    }

    fun updateProfile(
        tenantId: UUID,
        userId: UUID,
        username: String?,
        email: String,
        phone: String?,
        name: String?
    ): AppUser? {
        val now = OffsetDateTime.now(clock)
        dsl.update(table)
            .set(usernameField, username)
            .set(emailField, email)
            .set(phoneField, phone)
            .set(nameField, name)
            .set(updatedAtField, now)
            .where(userIdField.eq(userId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
        return findById(tenantId, userId)
    }

    fun updateStatus(tenantId: UUID, userId: UUID, enabled: Boolean): Boolean {
        val now = OffsetDateTime.now(clock)
        return dsl.update(table)
            .set(enabledField, enabled)
            .set(updatedAtField, now)
            .where(userIdField.eq(userId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute() > 0
    }

    fun updateLastLoginAt(tenantId: UUID, userId: UUID, lastLoginAt: OffsetDateTime): Boolean {
        val now = OffsetDateTime.now(clock)
        return dsl.update(table)
            .set(lastLoginAtField, lastLoginAt)
            .set(updatedAtField, now)
            .where(userIdField.eq(userId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute() > 0
    }

    fun setLegacyRoles(tenantId: UUID, userId: UUID, roles: List<String>) {
        val now = OffsetDateTime.now(clock)
        dsl.update(table)
            .set(rolesField, roles.toTypedArray())
            .set(updatedAtField, now)
            .where(userIdField.eq(userId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }

    private fun recordText(record: Record, fieldName: String): String? {
        val raw = record.get(fieldName) ?: return null
        return if (raw is String) raw else raw.toString()
    }

    private fun mapUser(record: Record): AppUser =
        AppUser(
            userId = record.get(userIdField),
            tenantId = record.get(tenantIdField),
            username = recordText(record, "username"),
            email = recordText(record, "email") ?: "",
            phone = recordText(record, "phone"),
            name = record.get(nameField),
            enabled = record.get(enabledField) ?: true,
            roles = record.get(rolesField)?.toList() ?: emptyList(),
            lastLoginAt = record.get(lastLoginAtField),
            createdAt = record.get(createdAtField),
            updatedAt = record.get(updatedAtField),
        )
}
