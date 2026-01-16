package com.secrux.repo

import com.secrux.domain.Tenant
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TenantRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {
    private val table = DSL.table("tenant")

    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val planField = DSL.field("plan", String::class.java)
    private val contactEmailField = DSL.field("contact_email", String::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)

    fun insertIfMissing(
        tenantId: UUID,
        name: String,
        plan: String = "standard",
        contactEmail: String? = null
    ): Boolean {
        val now = OffsetDateTime.now(clock)
        return dsl.insertInto(table)
            .set(tenantIdField, tenantId)
            .set(nameField, name)
            .set(planField, plan)
            .set(contactEmailField, contactEmail)
            .set(createdAtField, now)
            .set(updatedAtField, now)
            .onConflict(tenantIdField)
            .doNothing()
            .execute() > 0
    }

    fun findById(tenantId: UUID): Tenant? =
        dsl.selectFrom(table)
            .where(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne { mapTenant(it) }

    fun listActive(): List<Tenant> =
        dsl.selectFrom(table)
            .where(deletedAtField.isNull)
            .orderBy(createdAtField.desc())
            .fetch { mapTenant(it) }

    fun update(
        tenantId: UUID,
        name: String,
        contactEmail: String?
    ): Tenant? {
        val now = OffsetDateTime.now(clock)
        dsl.update(table)
            .set(nameField, name)
            .set(contactEmailField, contactEmail)
            .set(updatedAtField, now)
            .where(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
        return findById(tenantId)
    }

    private fun mapTenant(record: Record): Tenant =
        Tenant(
            tenantId = record.get(tenantIdField),
            name = record.get(nameField),
            plan = record.get(planField),
            contactEmail = record.get(contactEmailField),
            createdAt = record.get(createdAtField),
            updatedAt = record.get(updatedAtField)
        )
}
