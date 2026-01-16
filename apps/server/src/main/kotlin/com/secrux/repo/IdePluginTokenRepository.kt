package com.secrux.repo

import com.secrux.domain.IdePluginToken
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class IdePluginTokenRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    private val table = DSL.table("iam_ide_plugin_token")

    private val tokenIdField = DSL.field("token_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val userIdField = DSL.field("user_id", UUID::class.java)
    private val tokenHashField = DSL.field("token_hash", String::class.java)
    private val tokenHintField = DSL.field("token_hint", String::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val lastUsedAtField = DSL.field("last_used_at", OffsetDateTime::class.java)
    private val revokedAtField = DSL.field("revoked_at", OffsetDateTime::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)

    fun insert(
        tenantId: UUID,
        userId: UUID,
        tokenHash: String,
        tokenHint: String,
        name: String?
    ): IdePluginToken {
        val tokenId = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        dsl.insertInto(table)
            .set(tokenIdField, tokenId)
            .set(tenantIdField, tenantId)
            .set(userIdField, userId)
            .set(tokenHashField, tokenHash)
            .set(tokenHintField, tokenHint)
            .set(nameField, name)
            .set(createdAtField, now)
            .execute()
        return IdePluginToken(
            tokenId = tokenId,
            tenantId = tenantId,
            userId = userId,
            tokenHash = tokenHash,
            tokenHint = tokenHint,
            name = name,
            lastUsedAt = null,
            revokedAt = null,
            createdAt = now,
        )
    }

    fun listByUser(tenantId: UUID, userId: UUID): List<IdePluginToken> =
        dsl.selectFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(userIdField.eq(userId))
            .orderBy(createdAtField.desc())
            .fetch { mapToken(it) }

    fun findActiveByHash(tokenHash: String): IdePluginToken? =
        dsl.selectFrom(table)
            .where(tokenHashField.eq(tokenHash))
            .and(revokedAtField.isNull)
            .fetchOne { mapToken(it) }

    fun revokeById(tenantId: UUID, userId: UUID, tokenId: UUID): Boolean {
        val now = OffsetDateTime.now(clock)
        return dsl.update(table)
            .set(revokedAtField, now)
            .where(tokenIdField.eq(tokenId))
            .and(tenantIdField.eq(tenantId))
            .and(userIdField.eq(userId))
            .and(revokedAtField.isNull)
            .execute() > 0
    }

    fun touchLastUsedAt(tokenId: UUID, now: OffsetDateTime, thresholdSeconds: Long): Boolean {
        val threshold = now.minusSeconds(thresholdSeconds)
        return dsl.update(table)
            .set(lastUsedAtField, now)
            .where(tokenIdField.eq(tokenId))
            .and(revokedAtField.isNull)
            .and(lastUsedAtField.isNull.or(lastUsedAtField.lt(threshold)))
            .execute() > 0
    }

    private fun mapToken(record: Record): IdePluginToken =
        IdePluginToken(
            tokenId = record.get(tokenIdField),
            tenantId = record.get(tenantIdField),
            userId = record.get(userIdField),
            tokenHash = record.get(tokenHashField),
            tokenHint = record.get(tokenHintField),
            name = record.get(nameField),
            lastUsedAt = record.get(lastUsedAtField),
            revokedAt = record.get(revokedAtField),
            createdAt = record.get(createdAtField),
        )
}

