package com.secrux.service

import com.secrux.domain.RepositorySourceMode
import com.secrux.dto.ProjectRepositoryOverview
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ProjectOverviewCatalogQueryService(
    private val dsl: DSLContext
) {

    fun getRepositoryOverview(tenantId: UUID, projectId: UUID): ProjectRepositoryOverview {
        val table = DSL.table("repository")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val projectField = DSL.field("project_id", UUID::class.java)
        val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
        val sourceModeField = DSL.field("source_mode", String::class.java)
        val countField = DSL.count().`as`("cnt")

        val records =
            dsl.select(sourceModeField, countField)
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(projectField.eq(projectId))
                .and(deletedAtField.isNull)
                .groupBy(sourceModeField)
                .fetch()

        val bySourceMode =
            records.associate { r ->
                val mode = RepositorySourceMode.valueOf(r.get(sourceModeField).uppercase())
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                mode to count
            }

        return ProjectRepositoryOverview(
            total = bySourceMode.values.sum(),
            bySourceMode = bySourceMode
        )
    }
}

