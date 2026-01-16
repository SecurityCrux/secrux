package com.secrux.service

import com.secrux.domain.TaskStatus
import com.secrux.domain.TaskType
import com.secrux.dto.ProjectTaskOverview
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ProjectOverviewTaskQueryService(
    private val dsl: DSLContext
) {

    fun getTaskOverview(tenantId: UUID, projectId: UUID): ProjectTaskOverview {
        val table = DSL.table("task")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val projectField = DSL.field("project_id", UUID::class.java)
        val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
        val statusField = DSL.field("status", String::class.java)
        val typeField = DSL.field("type", String::class.java)
        val countField = DSL.count().`as`("cnt")

        val statusRecords =
            dsl.select(statusField, countField)
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(projectField.eq(projectId))
                .and(deletedAtField.isNull)
                .groupBy(statusField)
                .fetch()

        val byStatus =
            statusRecords.associate { r ->
                val status = TaskStatus.valueOf(r.get(statusField))
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                status to count
            }

        val typeRecords =
            dsl.select(typeField, countField)
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(projectField.eq(projectId))
                .and(deletedAtField.isNull)
                .groupBy(typeField)
                .fetch()

        val byType =
            typeRecords.associate { r ->
                val type = TaskType.valueOf(r.get(typeField))
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                type to count
            }

        val running = byStatus[TaskStatus.RUNNING] ?: 0
        val total = byStatus.values.sum()

        return ProjectTaskOverview(
            total = total,
            running = running,
            byStatus = byStatus,
            byType = byType
        )
    }
}

