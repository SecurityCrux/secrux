package com.secrux.service

import com.secrux.dto.OverviewAiSummary
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class OverviewAiQueryService(
    private val dsl: DSLContext
) {

    fun getAiSummary(tenantId: UUID, windowStart: OffsetDateTime): OverviewAiSummary {
        val table = DSL.table("ai_review")
        val tenantField = DSL.field("tenant_id", UUID::class.java)
        val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
        val reviewTypeField = DSL.field("review_type", String::class.java)
        val appliedAtField = DSL.field("applied_at", OffsetDateTime::class.java)
        val statusBeforeField = DSL.field("status_before", String::class.java)
        val statusAfterField = DSL.field("status_after", String::class.java)

        val queued =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(reviewTypeField.eq("AI"))
                .and(appliedAtField.isNull)
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val completedWindow =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(reviewTypeField.eq("AI"))
                .and(appliedAtField.isNotNull)
                .and(appliedAtField.ge(windowStart))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val autoAppliedWindow =
            dsl.selectCount()
                .from(table)
                .where(tenantField.eq(tenantId))
                .and(deletedAtField.isNull)
                .and(reviewTypeField.eq("AI"))
                .and(appliedAtField.isNotNull)
                .and(appliedAtField.ge(windowStart))
                .and(statusBeforeField.isNotNull)
                .and(statusAfterField.isNotNull)
                .and(statusBeforeField.ne(statusAfterField))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        return OverviewAiSummary(
            queued = queued,
            completedWindow = completedWindow,
            autoAppliedWindow = autoAppliedWindow
        )
    }
}

