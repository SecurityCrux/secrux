package com.secrux.repo

import com.secrux.domain.IamRole
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class IamRoleRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {
    private val table = DSL.table("iam_role")

    private val roleIdField = DSL.field("role_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val keyField = DSL.field("key", String::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val descriptionField = DSL.field("description", String::class.java)
    private val builtInField = DSL.field("built_in", Boolean::class.javaObjectType)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)

    fun insert(role: IamRole) {
        insert(dsl, role)
    }

    fun insert(ctx: DSLContext, role: IamRole) {
        ctx.insertInto(table)
            .set(roleIdField, role.roleId)
            .set(tenantIdField, role.tenantId)
            .set(keyField, role.key)
            .set(nameField, role.name)
            .set(descriptionField, role.description)
            .set(builtInField, role.builtIn)
            .set(createdAtField, role.createdAt)
            .set(updatedAtField, role.updatedAt)
            .set(deletedAtField, role.deletedAt)
            .execute()
    }

    fun updateMetadata(
        tenantId: UUID,
        roleId: UUID,
        name: String,
        description: String?
    ): IamRole? {
        val now = OffsetDateTime.now(clock)
        dsl.update(table)
            .set(nameField, name)
            .set(descriptionField, description)
            .set(updatedAtField, now)
            .where(roleIdField.eq(roleId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
        return findById(tenantId, roleId)
    }

    fun findById(tenantId: UUID, roleId: UUID): IamRole? =
        dsl.selectFrom(table)
            .where(roleIdField.eq(roleId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne { mapRole(it) }

    fun findByKey(tenantId: UUID, key: String): IamRole? =
        dsl.selectFrom(table)
            .where(keyField.eq(key))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne { mapRole(it) }

    fun list(
        tenantId: UUID,
        search: String?,
        limit: Int,
        offset: Int
    ): Pair<List<IamRole>, Long> {
        var condition = tenantIdField.eq(tenantId).and(deletedAtField.isNull)
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val like = "%${term.lowercase()}%"
            condition = condition.and(
                DSL.lower(keyField).like(like)
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
                .fetch { mapRole(it) }

        return items to total
    }

    fun listByIds(tenantId: UUID, roleIds: Collection<UUID>): List<IamRole> {
        if (roleIds.isEmpty()) return emptyList()
        return dsl.selectFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(roleIdField.`in`(roleIds))
            .and(deletedAtField.isNull)
            .fetch { mapRole(it) }
    }

    fun softDelete(tenantId: UUID, roleId: UUID): Boolean {
        val now = OffsetDateTime.now(clock)
        return dsl.update(table)
            .set(deletedAtField, now)
            .set(updatedAtField, now)
            .where(roleIdField.eq(roleId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute() > 0
    }

    private fun mapRole(record: Record): IamRole =
        IamRole(
            roleId = record.get(roleIdField),
            tenantId = record.get(tenantIdField),
            key = record.get(keyField),
            name = record.get(nameField),
            description = record.get(descriptionField),
            builtIn = record.get(builtInField) ?: false,
            createdAt = record.get(createdAtField),
            updatedAt = record.get(updatedAtField),
            deletedAt = record.get(deletedAtField),
        )
}
