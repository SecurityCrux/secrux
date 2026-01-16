package com.secrux.repo

import com.secrux.domain.IamRolePermission
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class IamRolePermissionRepository(
    private val dsl: DSLContext
) {
    private val table = DSL.table("iam_role_permission")

    private val idField = DSL.field("id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val roleIdField = DSL.field("role_id", UUID::class.java)
    private val permissionField = DSL.field("permission", String::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)

    fun listPermissions(tenantId: UUID, roleId: UUID): List<String> =
        dsl.select(permissionField)
            .from(table)
            .where(tenantIdField.eq(tenantId))
            .and(roleIdField.eq(roleId))
            .orderBy(permissionField.asc())
            .fetch(permissionField)

    fun deleteByRole(ctx: DSLContext, tenantId: UUID, roleId: UUID) {
        ctx.deleteFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(roleIdField.eq(roleId))
            .execute()
    }

    fun insertAll(ctx: DSLContext, items: List<IamRolePermission>) {
        if (items.isEmpty()) return
        ctx.batch(
            items.map { item ->
                ctx.insertInto(table)
                    .columns(idField, tenantIdField, roleIdField, permissionField, createdAtField)
                    .values(item.id, item.tenantId, item.roleId, item.permission, item.createdAt)
            }
        ).execute()
    }

    fun listByRoleIds(tenantId: UUID, roleIds: Collection<UUID>): List<IamRolePermission> {
        if (roleIds.isEmpty()) return emptyList()
        return dsl.selectFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(roleIdField.`in`(roleIds))
            .fetch { mapPerm(it) }
    }

    private fun mapPerm(record: Record): IamRolePermission =
        IamRolePermission(
            id = record.get(idField),
            tenantId = record.get(tenantIdField),
            roleId = record.get(roleIdField),
            permission = record.get(permissionField),
            createdAt = record.get(createdAtField),
        )
}

