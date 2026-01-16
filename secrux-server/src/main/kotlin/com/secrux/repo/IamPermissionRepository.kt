package com.secrux.repo

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class IamPermissionRepository(
    private val dsl: DSLContext
) {
    private val userRoleTable = DSL.table("iam_user_role")
    private val rolePermTable = DSL.table("iam_role_permission")

    private val urTenantField = DSL.field(DSL.name("ur", "tenant_id"), UUID::class.java)
    private val urUserField = DSL.field(DSL.name("ur", "user_id"), UUID::class.java)
    private val urRoleField = DSL.field(DSL.name("ur", "role_id"), UUID::class.java)

    private val rpTenantField = DSL.field(DSL.name("rp", "tenant_id"), UUID::class.java)
    private val rpRoleField = DSL.field(DSL.name("rp", "role_id"), UUID::class.java)
    private val rpPermissionField = DSL.field(DSL.name("rp", "permission"), String::class.java)

    fun listEffectivePermissions(tenantId: UUID, userId: UUID): Set<String> =
        dsl.selectDistinct(rpPermissionField)
            .from(userRoleTable.`as`("ur"))
            .join(rolePermTable.`as`("rp"))
            .on(urRoleField.eq(rpRoleField))
            .and(urTenantField.eq(rpTenantField))
            .where(urTenantField.eq(tenantId))
            .and(urUserField.eq(userId))
            .fetch(rpPermissionField)
            .filter { it.isNotBlank() }
            .toSet()
}

