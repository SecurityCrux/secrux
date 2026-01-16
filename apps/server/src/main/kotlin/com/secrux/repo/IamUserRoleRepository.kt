package com.secrux.repo

import com.secrux.domain.IamUserRole
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class IamUserRoleRepository(
    private val dsl: DSLContext
) {
    private val table = DSL.table("iam_user_role")

    private val idField = DSL.field("id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val userIdField = DSL.field("user_id", UUID::class.java)
    private val roleIdField = DSL.field("role_id", UUID::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)

    fun existsAnyForTenant(tenantId: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(table)
                .where(tenantIdField.eq(tenantId))
        )

    fun listRoleIdsByUser(tenantId: UUID, userId: UUID): List<UUID> =
        dsl.select(roleIdField)
            .from(table)
            .where(tenantIdField.eq(tenantId))
            .and(userIdField.eq(userId))
            .fetch(roleIdField)

    fun deleteByUser(ctx: DSLContext, tenantId: UUID, userId: UUID) {
        ctx.deleteFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(userIdField.eq(userId))
            .execute()
    }

    fun insertAll(ctx: DSLContext, items: List<IamUserRole>) {
        if (items.isEmpty()) return
        ctx.batch(
            items.map { item ->
                ctx.insertInto(table)
                    .columns(idField, tenantIdField, userIdField, roleIdField, createdAtField)
                    .values(item.id, item.tenantId, item.userId, item.roleId, item.createdAt)
            }
        ).execute()
    }

    fun listByUser(tenantId: UUID, userId: UUID): List<IamUserRole> =
        dsl.selectFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(userIdField.eq(userId))
            .orderBy(createdAtField.asc())
            .fetch { mapUserRole(it) }

    private fun mapUserRole(record: Record): IamUserRole =
        IamUserRole(
            id = record.get(idField),
            tenantId = record.get(tenantIdField),
            userId = record.get(userIdField),
            roleId = record.get(roleIdField),
            createdAt = record.get(createdAtField),
        )
}
