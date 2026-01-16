package com.secrux.service

import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.domain.TaskType
import com.secrux.dto.ProjectFindingOverview
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ProjectOverviewFindingQueryService(
    private val dsl: DSLContext
) {

    fun getFindingOverview(tenantId: UUID, projectId: UUID): ProjectFindingOverview {
        val findingTable = DSL.table(DSL.name("finding")).`as`("f")
        val taskTable = DSL.table(DSL.name("task")).`as`("t")

        val fTenant = DSL.field(DSL.name("f", "tenant_id"), UUID::class.java)
        val fTaskId = DSL.field(DSL.name("f", "task_id"), UUID::class.java)
        val fProjectId = DSL.field(DSL.name("f", "project_id"), UUID::class.java)
        val fDeleted = DSL.field(DSL.name("f", "deleted_at"), OffsetDateTime::class.java)
        val fStatus = DSL.field(DSL.name("f", "status"), String::class.java)
        val fSeverity = DSL.field(DSL.name("f", "severity"), String::class.java)

        val tTaskId = DSL.field(DSL.name("t", "task_id"), UUID::class.java)
        val tTenant = DSL.field(DSL.name("t", "tenant_id"), UUID::class.java)
        val tDeleted = DSL.field(DSL.name("t", "deleted_at"), OffsetDateTime::class.java)
        val tType = DSL.field(DSL.name("t", "type"), String::class.java)

        val baseCondition =
            fTenant.eq(tenantId)
                .and(tTenant.eq(tenantId))
                .and(fProjectId.eq(projectId))
                .and(fDeleted.isNull)
                .and(tDeleted.isNull)
                .and(tType.notIn(TaskType.SCA_CHECK.name, TaskType.SUPPLY_CHAIN.name))

        val open =
            dsl.selectCount()
                .from(findingTable)
                .join(taskTable)
                .on(fTaskId.eq(tTaskId))
                .where(baseCondition)
                .and(fStatus.eq(FindingStatus.OPEN.name))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val confirmed =
            dsl.selectCount()
                .from(findingTable)
                .join(taskTable)
                .on(fTaskId.eq(tTaskId))
                .where(baseCondition)
                .and(fStatus.eq(FindingStatus.CONFIRMED.name))
                .fetchOne(0, Int::class.javaObjectType)
                ?: 0

        val countField = DSL.count().`as`("cnt")
        val severityRecords =
            dsl.select(fSeverity, countField)
                .from(findingTable)
                .join(taskTable)
                .on(fTaskId.eq(tTaskId))
                .where(baseCondition)
                .and(fStatus.`in`(FindingStatus.OPEN.name, FindingStatus.CONFIRMED.name))
                .groupBy(fSeverity)
                .fetch()

        val bySeverity =
            severityRecords.associate { r ->
                val sev = Severity.valueOf(r.get(fSeverity))
                val count = (r.get(countField) as? Number)?.toInt() ?: 0
                sev to count
            }

        return ProjectFindingOverview(
            open = open,
            confirmed = confirmed,
            bySeverityUnresolved = bySeverity
        )
    }
}
