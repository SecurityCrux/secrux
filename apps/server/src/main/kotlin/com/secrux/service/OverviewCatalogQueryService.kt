package com.secrux.service

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class OverviewCatalogQueryService(
    private val dsl: DSLContext
) {

    fun countProjects(tenantId: UUID): Int {
        val table = DSL.table("project")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
        return dsl.selectCount()
            .from(table)
            .where(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne(0, Int::class.javaObjectType)
            ?: 0
    }

    fun countRepositories(tenantId: UUID): Int {
        val table = DSL.table("repository")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
        return dsl.selectCount()
            .from(table)
            .where(tenantField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne(0, Int::class.javaObjectType)
            ?: 0
    }
}

