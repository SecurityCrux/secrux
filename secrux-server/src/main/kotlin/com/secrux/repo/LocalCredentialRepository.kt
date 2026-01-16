package com.secrux.repo

import com.secrux.domain.LocalCredential
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class LocalCredentialRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {
    private val table = DSL.table("iam_local_credential")

    private val userIdField = DSL.field("user_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val passwordHashField = DSL.field("password_hash", String::class.java)
    private val mustChangeField = DSL.field("must_change_password", Boolean::class.javaObjectType)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun findByUserId(userId: UUID): LocalCredential? =
        dsl.selectFrom(table)
            .where(userIdField.eq(userId))
            .fetchOne { mapCredential(it) }

    fun upsert(
        userId: UUID,
        tenantId: UUID,
        passwordHash: String,
        mustChangePassword: Boolean
    ) {
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(table)
            .set(userIdField, userId)
            .set(tenantIdField, tenantId)
            .set(passwordHashField, passwordHash)
            .set(mustChangeField, mustChangePassword)
            .set(createdAtField, now)
            .set(updatedAtField, now)
            .onConflict(userIdField)
            .doUpdate()
            .set(passwordHashField, passwordHash)
            .set(mustChangeField, mustChangePassword)
            .set(updatedAtField, now)
            .execute()
    }

    private fun mapCredential(record: Record): LocalCredential =
        LocalCredential(
            userId = record.get(userIdField),
            tenantId = record.get(tenantIdField),
            passwordHash = record.get(passwordHashField),
            mustChangePassword = record.get(mustChangeField) ?: false,
            createdAt = record.get(createdAtField),
            updatedAt = record.get(updatedAtField),
        )
}

