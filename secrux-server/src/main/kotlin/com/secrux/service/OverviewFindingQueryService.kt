package com.secrux.service

import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.dto.OverviewFindingItem
import com.secrux.dto.OverviewFindingSummary
import com.secrux.dto.OverviewFindingTrendPoint
import com.secrux.dto.PageResponse
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class OverviewFindingQueryService(
    private val dsl: DSLContext,
    private val clock: Clock
) {

    fun listTopFindings(tenantId: UUID, limit: Int, offset: Int): PageResponse<OverviewFindingItem> {
        val table = DSL.table("finding").`as`("f")
        val tenantField = DSL.field(DSL.name("f", "tenant_id"), UUID::class.java)
        val deletedAtField = DSL.field(DSL.name("f", "deleted_at"), OffsetDateTime::class.java)
        val createdAtField = DSL.field(DSL.name("f", "created_at"), OffsetDateTime::class.java)
        val findingIdField = DSL.field(DSL.name("f", "finding_id"), UUID::class.java)
        val taskIdField = DSL.field(DSL.name("f", "task_id"), UUID::class.java)
        val projectIdField = DSL.field(DSL.name("f", "project_id"), UUID::class.java)
        val ruleIdField = DSL.field(DSL.name("f", "rule_id"), String::class.java)
        val severityField = DSL.field(DSL.name("f", "severity"), String::class.java)
        val statusField = DSL.field(DSL.name("f", "status"), String::class.java)
        val introducedByField = DSL.field(DSL.name("f", "introduced_by"), String::class.java)

        val condition =
            tenantField.eq(tenantId)
                .and(deletedAtField.isNull)
                .and(statusField.`in`(FindingStatus.OPEN.name, FindingStatus.CONFIRMED.name))

        val severityRank =
            DSL
                .case_()
                .`when`(severityField.eq(Severity.CRITICAL.name), 5)
                .`when`(severityField.eq(Severity.HIGH.name), 4)
                .`when`(severityField.eq(Severity.MEDIUM.name), 3)
                .`when`(severityField.eq(Severity.LOW.name), 2)
                .`when`(severityField.eq(Severity.INFO.name), 1)
                .otherwise(0)

        val records =
            dsl.select(
                findingIdField,
                taskIdField,
                projectIdField,
                ruleIdField,
                severityField,
                statusField,
                introducedByField,
                createdAtField
            )
                .from(table)
                .where(condition)
                .orderBy(severityRank.desc(), createdAtField.desc())
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
                OverviewFindingItem(
                    findingId = r.get(findingIdField),
                    taskId = r.get(taskIdField),
                    projectId = r.get(projectIdField),
                    ruleId = r.get(ruleIdField),
                    severity = Severity.valueOf(r.get(severityField)),
                    status = FindingStatus.valueOf(r.get(statusField)),
                    introducedBy = r.get(introducedByField),
                    createdAt = r.get(createdAtField).toString()
                )
            }

        return PageResponse(items = items, total = total, limit = limit, offset = offset)
    }

    fun getFindingSummary(tenantId: UUID, windowStart: OffsetDateTime): OverviewFindingSummary {
        val table = DSL.table("finding")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
        val statusField = DSL.field("status", String::class.java)
        val severityField = DSL.field("severity", String::class.java)

        val open =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(statusField.eq(FindingStatus.OPEN.name))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val confirmed =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(statusField.eq(FindingStatus.CONFIRMED.name))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val countField = DSL.count().`as`("cnt")
        val bySeverityRecords =
            dsl.select(severityField, countField)
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(statusField.`in`(FindingStatus.OPEN.name, FindingStatus.CONFIRMED.name))
                .groupBy(severityField)
                .fetch()

        val bySeverity =
            bySeverityRecords.associate { r ->
                val severity = Severity.valueOf(r.get(severityField))
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                severity to count
            }

        val trend = buildFindingTrend(tenantId, windowStart)

        return OverviewFindingSummary(
            open = open,
            confirmed = confirmed,
            bySeverityUnresolved = bySeverity,
            trendWindow = trend
        )
    }

    private fun buildFindingTrend(tenantId: UUID, windowStart: OffsetDateTime): List<OverviewFindingTrendPoint> {
        val findingTable = DSL.table("finding").`as`("f")
        val fTenant = DSL.field(DSL.name("f", "tenant_id"), UUID::class.java)
        val fDeleted = DSL.field(DSL.name("f", "deleted_at"), OffsetDateTime::class.java)
        val fCreated = DSL.field(DSL.name("f", "created_at"), OffsetDateTime::class.java)

        val reviewTable = DSL.table("ai_review").`as`("r")
        val rTenant = DSL.field(DSL.name("r", "tenant_id"), UUID::class.java)
        val rDeleted = DSL.field(DSL.name("r", "deleted_at"), OffsetDateTime::class.java)
        val rApplied = DSL.field(DSL.name("r", "applied_at"), OffsetDateTime::class.java)
        val rStatusAfter = DSL.field(DSL.name("r", "status_after"), String::class.java)

        val day = DSL.field("date_trunc('day', {0})", Any::class.java, fCreated)
        val newCounts =
            dsl.select(day.`as`("day"), DSL.count().`as`("cnt"))
                .from(findingTable)
                .where(fTenant.eq(tenantId))
                .and(fDeleted.isNull)
                .and(fCreated.ge(windowStart))
                .groupBy(DSL.field("day"))
                .fetch()
                .associate { r -> toLocalDate(r.get("day")) to (r.get("cnt") as Number).toInt() }

        val closedDay = DSL.field("date_trunc('day', {0})", Any::class.java, rApplied)
        val closedStatuses = listOf(FindingStatus.RESOLVED.name, FindingStatus.FALSE_POSITIVE.name, FindingStatus.WONT_FIX.name)
        val closedCounts =
            dsl.select(closedDay.`as`("day"), DSL.count().`as`("cnt"))
                .from(reviewTable)
                .where(rTenant.eq(tenantId))
                .and(rDeleted.isNull)
                .and(rApplied.isNotNull)
                .and(rApplied.ge(windowStart))
                .and(rStatusAfter.`in`(closedStatuses))
                .groupBy(DSL.field("day"))
                .fetch()
                .associate { r -> toLocalDate(r.get("day")) to (r.get("cnt") as Number).toInt() }

        val startDate = windowStart.toLocalDate()
        val today = OffsetDateTime.now(clock).toLocalDate()
        val points = mutableListOf<OverviewFindingTrendPoint>()
        var cursor = startDate
        while (!cursor.isAfter(today)) {
            points.add(
                OverviewFindingTrendPoint(
                    date = cursor.toString(),
                    newCount = newCounts[cursor] ?: 0,
                    closedCount = closedCounts[cursor] ?: 0
                )
            )
            cursor = cursor.plusDays(1)
        }
        return points
    }

    private fun toLocalDate(value: Any?): java.time.LocalDate {
        return when (value) {
            is OffsetDateTime -> value.toLocalDate()
            is java.time.LocalDateTime -> value.toLocalDate()
            is java.sql.Timestamp -> value.toLocalDateTime().toLocalDate()
            is java.util.Date -> value.toInstant().atOffset(OffsetDateTime.now(clock).offset).toLocalDate()
            else -> OffsetDateTime.now(clock).toLocalDate()
        }
    }
}

