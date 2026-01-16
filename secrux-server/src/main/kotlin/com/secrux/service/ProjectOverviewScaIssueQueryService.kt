package com.secrux.service

import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.dto.ProjectScaIssueOverview
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ProjectOverviewScaIssueQueryService(
    private val dsl: DSLContext
) {

    fun getScaIssueOverview(tenantId: UUID, projectId: UUID): ProjectScaIssueOverview {
        val table = DSL.table("sca_issue").`as`("i")
        val tenantField = DSL.field(DSL.name("i", "tenant_id"), UUID::class.java)
        val projectField = DSL.field(DSL.name("i", "project_id"), UUID::class.java)
        val deletedAtField = DSL.field(DSL.name("i", "deleted_at"), OffsetDateTime::class.java)
        val statusField = DSL.field(DSL.name("i", "status"), String::class.java)
        val severityField = DSL.field(DSL.name("i", "severity"), String::class.java)

        val baseCondition =
            tenantField.eq(tenantId)
                .and(projectField.eq(projectId))
                .and(deletedAtField.isNull)

        val open =
            dsl.selectCount()
                .from(table)
                .where(baseCondition)
                .and(statusField.eq(FindingStatus.OPEN.name))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val confirmed =
            dsl.selectCount()
                .from(table)
                .where(baseCondition)
                .and(statusField.eq(FindingStatus.CONFIRMED.name))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val countField = DSL.count().`as`("cnt")
        val severityRecords =
            dsl.select(severityField, countField)
                .from(table)
                .where(baseCondition)
                .and(statusField.`in`(FindingStatus.OPEN.name, FindingStatus.CONFIRMED.name))
                .groupBy(severityField)
                .fetch()

        val bySeverity =
            severityRecords.associate { r ->
                val sev = Severity.valueOf(r.get(severityField))
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                sev to count
            }

        return ProjectScaIssueOverview(
            open = open,
            confirmed = confirmed,
            bySeverityUnresolved = bySeverity
        )
    }
}

