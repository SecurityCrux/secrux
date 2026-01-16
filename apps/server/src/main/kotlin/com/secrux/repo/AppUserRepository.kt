package com.secrux.repo

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class AppUserRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {

    private val table = DSL.table("app_user")
    private val userIdField = DSL.field("user_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val usernameField = DSL.field("username", String::class.java)
    private val emailField = DSL.field("email", String::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val rolesField = DSL.field("roles", Array<String>::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun upsert(
        userId: UUID,
        tenantId: UUID,
        email: String,
        username: String?,
        name: String?,
        roles: List<String>
    ) {
        val now = OffsetDateTime.now(clock)
        val roleArray = roles.toTypedArray()
        dsl.insertInto(table)
            .set(userIdField, userId)
            .set(tenantIdField, tenantId)
            .set(usernameField, username)
            .set(emailField, email)
            .set(nameField, name)
            .set(rolesField, roleArray)
            .set(createdAtField, now)
            .set(updatedAtField, now)
            .onConflict(userIdField)
            .doUpdate()
            .set(tenantIdField, tenantId)
            .set(usernameField, username)
            .set(emailField, email)
            .set(nameField, name)
            .set(rolesField, roleArray)
            .set(updatedAtField, now)
            .execute()
    }
}
