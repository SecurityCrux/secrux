package com.secrux.repo

import com.secrux.domain.RefreshToken
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class RefreshTokenRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {
    private val table = DSL.table("iam_refresh_token")

    private val tokenIdField = DSL.field("token_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val userIdField = DSL.field("user_id", UUID::class.java)
    private val tokenHashField = DSL.field("token_hash", String::class.java)
    private val expiresAtField = DSL.field("expires_at", OffsetDateTime::class.java)
    private val revokedAtField = DSL.field("revoked_at", OffsetDateTime::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)

    fun insert(
        tenantId: UUID,
        userId: UUID,
        tokenHash: String,
        expiresAt: OffsetDateTime
    ): UUID {
        return insert(dsl, tenantId, userId, tokenHash, expiresAt)
    }

    fun insert(
        ctx: DSLContext,
        tenantId: UUID,
        userId: UUID,
        tokenHash: String,
        expiresAt: OffsetDateTime
    ): UUID {
        val tokenId = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        ctx.insertInto(table)
            .set(tokenIdField, tokenId)
            .set(tenantIdField, tenantId)
            .set(userIdField, userId)
            .set(tokenHashField, tokenHash)
            .set(expiresAtField, expiresAt)
            .set(createdAtField, now)
            .execute()
        return tokenId
    }

    fun findActiveByHash(tokenHash: String, now: OffsetDateTime): RefreshToken? =
        dsl.selectFrom(table)
            .where(tokenHashField.eq(tokenHash))
            .and(revokedAtField.isNull)
            .and(expiresAtField.gt(now))
            .fetchOne { mapToken(it) }

    fun revoke(tokenId: UUID): Boolean {
        return revoke(dsl, tokenId)
    }

    fun revoke(ctx: DSLContext, tokenId: UUID): Boolean {
        val now = OffsetDateTime.now(clock)
        return ctx.update(table)
            .set(revokedAtField, now)
            .where(tokenIdField.eq(tokenId))
            .and(revokedAtField.isNull)
            .execute() > 0
    }

    private fun mapToken(record: Record): RefreshToken =
        RefreshToken(
            tokenId = record.get(tokenIdField),
            tenantId = record.get(tenantIdField),
            userId = record.get(userIdField),
            tokenHash = record.get(tokenHashField),
            expiresAt = record.get(expiresAtField),
            revokedAt = record.get(revokedAtField),
            createdAt = record.get(createdAtField),
        )
}
