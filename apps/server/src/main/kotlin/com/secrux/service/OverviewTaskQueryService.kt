package com.secrux.service

import com.secrux.domain.TaskStatus
import com.secrux.dto.OverviewTaskItem
import com.secrux.dto.OverviewTaskSummary
import com.secrux.dto.PageResponse
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.max

@Service
class OverviewTaskQueryService(
    private val dsl: DSLContext,
    private val clock: Clock
) {

    fun getTaskSummary(
        tenantId: UUID,
        windowStart: OffsetDateTime,
        dayStart: OffsetDateTime
    ): OverviewTaskSummary {
        val table = DSL.table("task")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
        val statusField = DSL.field("status", String::class.java)
        val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
        val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)

        val countField = DSL.count().`as`("cnt")
        val byStatusRecords =
            dsl.select(statusField, countField)
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .groupBy(statusField)
                .fetch()

        val byStatus =
            byStatusRecords.associate { r ->
                val status = TaskStatus.valueOf(r.get(statusField))
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                status to count
            }

        val running = byStatus[TaskStatus.RUNNING] ?: 0

        val failed24h =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(statusField.eq(TaskStatus.FAILED.name))
                .and(DSL.coalesce(updatedAtField, createdAtField).ge(dayStart))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val totalWindow =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(createdAtField.ge(windowStart))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val terminalStatuses = listOf(TaskStatus.SUCCEEDED.name, TaskStatus.FAILED.name, TaskStatus.CANCELED.name)
        val terminalTotalWindow =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(createdAtField.ge(windowStart))
                .and(statusField.`in`(terminalStatuses))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val succeededWindow =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(createdAtField.ge(windowStart))
                .and(statusField.eq(TaskStatus.SUCCEEDED.name))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val successRateWindow =
            if (terminalTotalWindow <= 0) 0.0 else succeededWindow.toDouble() / terminalTotalWindow.toDouble()

        return OverviewTaskSummary(
            running = running,
            failed24h = failed24h,
            totalWindow = totalWindow,
            successRateWindow = successRateWindow,
            byStatus = byStatus
        )
    }

    fun listRecentTasks(tenantId: UUID, limit: Int, offset: Int): PageResponse<OverviewTaskItem> {
        val table = DSL.table("task").`as`("t")
        val tenantField = DSL.field(DSL.name("t", "tenant_id"), UUID::class.java)
        val deletedAtField = DSL.field(DSL.name("t", "deleted_at"), OffsetDateTime::class.java)
        val createdAtField = DSL.field(DSL.name("t", "created_at"), OffsetDateTime::class.java)
        val updatedAtField = DSL.field(DSL.name("t", "updated_at"), OffsetDateTime::class.java)
        val taskIdField = DSL.field(DSL.name("t", "task_id"), UUID::class.java)
        val projectIdField = DSL.field(DSL.name("t", "project_id"), UUID::class.java)
        val repoIdField = DSL.field(DSL.name("t", "repo_id"), UUID::class.java)
        val statusField = DSL.field(DSL.name("t", "status"), String::class.java)
        val typeField = DSL.field(DSL.name("t", "type"), String::class.java)
        val nameField = DSL.field(DSL.name("t", "name"), String::class.java)
        val correlationField = DSL.field(DSL.name("t", "correlation_id"), String::class.java)

        val condition = tenantField.eq(tenantId).and(deletedAtField.isNull)
        val records =
            dsl.select(
                taskIdField,
                projectIdField,
                repoIdField,
                statusField,
                typeField,
                nameField,
                correlationField,
                createdAtField,
                updatedAtField
            )
                .from(table)
                .where(condition)
                .orderBy(createdAtField.desc())
                .limit(limit)
                .offset(offset)
                .fetch()

        val total =
            dsl.selectCount()
                .from(table)
                .where(condition)
                .fetchOne(0, Long::class.javaObjectType)
                ?: 0L

        val items =
            records.map { r ->
                OverviewTaskItem(
                    taskId = r.get(taskIdField),
                    projectId = r.get(projectIdField),
                    repoId = r.get(repoIdField),
                    status = TaskStatus.valueOf(r.get(statusField)),
                    type = r.get(typeField),
                    name = r.get(nameField),
                    correlationId = r.get(correlationField),
                    createdAt = r.get(createdAtField).toString(),
                    updatedAt = r.get(updatedAtField)?.toString()
                )
            }
        return PageResponse(items = items, total = total, limit = limit, offset = offset)
    }

    fun listStuckTasks(
        tenantId: UUID,
        thresholdSeconds: Long,
        limit: Int,
        offset: Int
    ): PageResponse<OverviewTaskItem> {
        val table = DSL.table("task").`as`("t")
        val tenantField = DSL.field(DSL.name("t", "tenant_id"), UUID::class.java)
        val deletedAtField = DSL.field(DSL.name("t", "deleted_at"), OffsetDateTime::class.java)
        val createdAtField = DSL.field(DSL.name("t", "created_at"), OffsetDateTime::class.java)
        val updatedAtField = DSL.field(DSL.name("t", "updated_at"), OffsetDateTime::class.java)
        val taskIdField = DSL.field(DSL.name("t", "task_id"), UUID::class.java)
        val projectIdField = DSL.field(DSL.name("t", "project_id"), UUID::class.java)
        val repoIdField = DSL.field(DSL.name("t", "repo_id"), UUID::class.java)
        val statusField = DSL.field(DSL.name("t", "status"), String::class.java)
        val typeField = DSL.field(DSL.name("t", "type"), String::class.java)
        val nameField = DSL.field(DSL.name("t", "name"), String::class.java)
        val correlationField = DSL.field(DSL.name("t", "correlation_id"), String::class.java)

        val now = OffsetDateTime.now(clock)
        val deadline = now.minusSeconds(max(60, thresholdSeconds))
        val effectiveUpdated = DSL.coalesce(updatedAtField, createdAtField)
        val activeStatuses = listOf(TaskStatus.PENDING.name, TaskStatus.RUNNING.name)

        val condition =
            tenantField.eq(tenantId)
                .and(deletedAtField.isNull)
                .and(statusField.`in`(activeStatuses))
                .and(effectiveUpdated.lt(deadline))

        val records =
            dsl.select(
                taskIdField,
                projectIdField,
                repoIdField,
                statusField,
                typeField,
                nameField,
                correlationField,
                createdAtField,
                updatedAtField
            )
                .from(table)
                .where(condition)
                .orderBy(effectiveUpdated.asc())
                .limit(limit)
                .offset(offset)
                .fetch()

        val total =
            dsl.selectCount()
                .from(table)
                .where(condition)
                .fetchOne(0, Long::class.javaObjectType)
                ?: 0L

        val items =
            records.map { r ->
                OverviewTaskItem(
                    taskId = r.get(taskIdField),
                    projectId = r.get(projectIdField),
                    repoId = r.get(repoIdField),
                    status = TaskStatus.valueOf(r.get(statusField)),
                    type = r.get(typeField),
                    name = r.get(nameField),
                    correlationId = r.get(correlationField),
                    createdAt = r.get(createdAtField).toString(),
                    updatedAt = r.get(updatedAtField)?.toString()
                )
            }
        return PageResponse(items = items, total = total, limit = limit, offset = offset)
    }
}
