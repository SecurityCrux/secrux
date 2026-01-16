package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.Finding
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.domain.TaskType
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class FindingRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    private val table = DSL.table("finding")
    private val findingIdField = DSL.field("finding_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val taskField = DSL.field("task_id", UUID::class.java)
    private val projectField = DSL.field("project_id", UUID::class.java)
    private val sourceEngineField = DSL.field("source_engine", String::class.java)
    private val ruleField = DSL.field("rule_id", String::class.java)
    private val locationField = DSL.field("location", JSONB::class.java)
    private val evidenceField = DSL.field("evidence", JSONB::class.java)
    private val severityField = DSL.field("severity", String::class.java)
    private val fingerprintField = DSL.field("fingerprint", String::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val introducedByField = DSL.field("introduced_by", String::class.java)
    private val fixVersionField = DSL.field("fix_version", String::class.java)
    private val exploitField = DSL.field("exploit_maturity", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
    private val deletedFlag = deletedAtField.isNull

    fun upsertAll(findings: List<Finding>) {
        findings.forEach { finding ->
            dsl
                .insertInto(table)
                .set(findingIdField, finding.findingId)
                .set(tenantField, finding.tenantId)
                .set(taskField, finding.taskId)
                .set(projectField, finding.projectId)
                .set(sourceEngineField, finding.sourceEngine)
                .set(ruleField, finding.ruleId)
                .set(locationField, objectMapper.toJsonb(finding.location))
                .set(evidenceField, objectMapper.toJsonb(finding.evidence ?: emptyMap<String, Any?>()))
                .set(severityField, finding.severity.name)
                .set(fingerprintField, finding.fingerprint)
                .set(statusField, finding.status.name)
                .set(introducedByField, finding.introducedBy)
                .set(fixVersionField, finding.fixVersion)
                .set(exploitField, finding.exploitMaturity)
                .set(createdField, finding.createdAt)
                .set(updatedField, finding.updatedAt)
                .onConflict(findingIdField)
                .doUpdate()
                .set(statusField, finding.status.name)
                .set(updatedField, finding.updatedAt)
                .set(fixVersionField, finding.fixVersion)
                .execute()
        }
    }

    fun listByTask(
        tenantId: UUID,
        taskId: UUID,
    ): List<Finding> =
        dsl
            .selectFrom(table)
            .where(activeCondition(tenantId, taskId))
            .orderBy(createdField.desc())
            .fetch { mapFinding(it) }

    fun listIdsByTask(
        tenantId: UUID,
        taskId: UUID,
    ): List<UUID> =
        dsl
            .select(findingIdField)
            .from(table)
            .where(activeCondition(tenantId, taskId))
            .fetch(findingIdField)

    fun listIdsByProject(
        tenantId: UUID,
        projectId: UUID,
    ): List<UUID> =
        listFindingIdsFilteredByTaskType(
            tenantId = tenantId,
            fromTable = DSL.table(DSL.name("finding")).`as`("f"),
            filterField = DSL.field(DSL.name("f", "project_id"), UUID::class.java),
            filterValue = projectId
        )

    fun listIdsByRepo(
        tenantId: UUID,
        repoId: UUID,
    ): List<UUID> {
        val findingTable = DSL.table(DSL.name("finding")).`as`("f")
        val taskTable = DSL.table(DSL.name("task")).`as`("t")
        val fId = DSL.field(DSL.name("f", "finding_id"), UUID::class.java)
        val fTaskId = DSL.field(DSL.name("f", "task_id"), UUID::class.java)
        val fTenant = DSL.field(DSL.name("f", "tenant_id"), UUID::class.java)
        val fDeleted = DSL.field(DSL.name("f", "deleted_at"), OffsetDateTime::class.java)
        val tTaskId = DSL.field(DSL.name("t", "task_id"), UUID::class.java)
        val tTenant = DSL.field(DSL.name("t", "tenant_id"), UUID::class.java)
        val tRepo = DSL.field(DSL.name("t", "repo_id"), UUID::class.java)
        val tType = DSL.field(DSL.name("t", "type"), String::class.java)
        val tDeleted = DSL.field(DSL.name("t", "deleted_at"), OffsetDateTime::class.java)

        return dsl
            .select(fId)
            .from(findingTable)
            .join(taskTable)
            .on(fTaskId.eq(tTaskId))
            .where(fTenant.eq(tenantId))
            .and(tTenant.eq(tenantId))
            .and(tRepo.eq(repoId))
            .and(fDeleted.isNull)
            .and(tDeleted.isNull)
            .and(tType.notIn(TaskType.SCA_CHECK.name, TaskType.SUPPLY_CHAIN.name))
            .fetch(fId)
    }

    fun listByTaskPaged(
        tenantId: UUID,
        taskId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Pair<List<Finding>, Long> {
        var condition: Condition = activeCondition(tenantId, taskId)
        status?.let { condition = condition.and(statusField.eq(it.name)) }
        severity?.let { condition = condition.and(severityField.eq(it.name)) }
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val like = "%${term.lowercase()}%"
            val ruleLower = DSL.lower(ruleField)
            val engineLower = DSL.lower(sourceEngineField)
            val introducedLower = DSL.lower(introducedByField)
            val locationLower = DSL.lower(locationField.cast(String::class.java))
            condition =
                condition.and(
                    ruleLower
                        .like(like)
                        .or(engineLower.like(like))
                        .or(introducedLower.like(like))
                        .or(locationLower.like(like)),
                )
        }
        val records =
            dsl
                .selectFrom(table)
                .where(condition)
                .orderBy(createdField.desc())
                .limit(limit)
                .offset(offset)
                .fetch { mapFinding(it) }
        val total =
            dsl
                .selectCount()
                .from(table)
                .where(condition)
                .fetchOne(0, Long::class.javaObjectType)
                ?: 0L
        return records to total
    }

    fun findById(
        findingId: UUID,
        tenantId: UUID,
    ): Finding? =
        dsl
            .selectFrom(table)
            .where(findingIdField.eq(findingId))
            .and(tenantField.eq(tenantId))
            .and(deletedFlag)
            .fetchOne { mapFinding(it) }

    fun findByIds(
        tenantId: UUID,
        findingIds: Collection<UUID>,
    ): List<Finding> {
        if (findingIds.isEmpty()) {
            return emptyList()
        }
        return dsl
            .selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(findingIdField.`in`(findingIds))
            .and(deletedFlag)
            .fetch { mapFinding(it) }
    }

    fun updateStatus(
        findingId: UUID,
        tenantId: UUID,
        status: FindingStatus,
        fixVersion: String?,
    ) {
        dsl
            .update(table)
            .set(statusField, status.name)
            .set(fixVersionField, fixVersion)
            .set(updatedField, DSL.currentOffsetDateTime())
            .where(findingIdField.eq(findingId))
            .and(tenantField.eq(tenantId))
            .and(deletedFlag)
            .execute()
    }

    fun updateStatusBatch(
        tenantId: UUID,
        findingIds: Collection<UUID>,
        status: FindingStatus,
        fixVersion: String?,
    ): Int {
        if (findingIds.isEmpty()) {
            return 0
        }
        return dsl
            .update(table)
            .set(statusField, status.name)
            .set(fixVersionField, fixVersion)
            .set(updatedField, DSL.currentOffsetDateTime())
            .where(tenantField.eq(tenantId))
            .and(findingIdField.`in`(findingIds))
            .and(deletedFlag)
            .execute()
    }

    fun softDelete(
        findingId: UUID,
        tenantId: UUID,
    ): Boolean =
        dsl
            .update(table)
            .set(deletedAtField, DSL.currentOffsetDateTime())
            .where(findingIdField.eq(findingId))
            .and(tenantField.eq(tenantId))
            .and(deletedFlag)
            .execute() > 0

    fun listByProjectPaged(
        tenantId: UUID,
        projectId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Pair<List<Finding>, Long> {
        val findingTable = DSL.table(DSL.name("finding")).`as`("f")
        val taskTable = DSL.table(DSL.name("task")).`as`("t")
        val fFindingId = DSL.field(DSL.name("f", "finding_id"), UUID::class.java)
        val fTenant = DSL.field(DSL.name("f", "tenant_id"), UUID::class.java)
        val fTaskId = DSL.field(DSL.name("f", "task_id"), UUID::class.java)
        val fProjectId = DSL.field(DSL.name("f", "project_id"), UUID::class.java)
        val fSourceEngine = DSL.field(DSL.name("f", "source_engine"), String::class.java)
        val fRuleId = DSL.field(DSL.name("f", "rule_id"), String::class.java)
        val fLocation = DSL.field(DSL.name("f", "location"), JSONB::class.java)
        val fEvidence = DSL.field(DSL.name("f", "evidence"), JSONB::class.java)
        val fSeverity = DSL.field(DSL.name("f", "severity"), String::class.java)
        val fFingerprint = DSL.field(DSL.name("f", "fingerprint"), String::class.java)
        val fStatus = DSL.field(DSL.name("f", "status"), String::class.java)
        val fIntroducedBy = DSL.field(DSL.name("f", "introduced_by"), String::class.java)
        val fFixVersion = DSL.field(DSL.name("f", "fix_version"), String::class.java)
        val fExploit = DSL.field(DSL.name("f", "exploit_maturity"), String::class.java)
        val fCreatedAt = DSL.field(DSL.name("f", "created_at"), OffsetDateTime::class.java)
        val fUpdatedAt = DSL.field(DSL.name("f", "updated_at"), OffsetDateTime::class.java)
        val fDeletedAt = DSL.field(DSL.name("f", "deleted_at"), OffsetDateTime::class.java)
        val tTaskId = DSL.field(DSL.name("t", "task_id"), UUID::class.java)
        val tTenant = DSL.field(DSL.name("t", "tenant_id"), UUID::class.java)
        val tType = DSL.field(DSL.name("t", "type"), String::class.java)
        val tDeleted = DSL.field(DSL.name("t", "deleted_at"), OffsetDateTime::class.java)

        val condition =
            fTenant
                .eq(tenantId)
                .and(tTenant.eq(tenantId))
                .and(fProjectId.eq(projectId))
                .and(fDeletedAt.isNull)
                .and(tDeleted.isNull)
                .and(tType.notIn(TaskType.SCA_CHECK.name, TaskType.SUPPLY_CHAIN.name))
                .let { base ->
                    var cond: Condition = base
                    status?.let { cond = cond.and(fStatus.eq(it.name)) }
                    severity?.let { cond = cond.and(fSeverity.eq(it.name)) }
                    search?.takeIf { it.isNotBlank() }?.let { term ->
                        val like = "%${term.lowercase()}%"
                        val ruleLower = DSL.lower(fRuleId)
                        val engineLower = DSL.lower(fSourceEngine)
                        val introducedLower = DSL.lower(fIntroducedBy)
                        val locationLower = DSL.lower(fLocation.cast(String::class.java))
                        cond =
                            cond.and(
                                ruleLower
                                    .like(like)
                                    .or(engineLower.like(like))
                                    .or(introducedLower.like(like))
                                    .or(locationLower.like(like)),
                            )
                    }
                    cond
                }

        val records =
            dsl
                .select(
                    fFindingId,
                    fTenant,
                    fTaskId,
                    fProjectId,
                    fSourceEngine,
                    fRuleId,
                    fLocation,
                    fEvidence,
                    fSeverity,
                    fFingerprint,
                    fStatus,
                    fIntroducedBy,
                    fFixVersion,
                    fExploit,
                    fCreatedAt,
                    fUpdatedAt
                ).from(findingTable)
                .join(taskTable)
                .on(fTaskId.eq(tTaskId))
                .where(condition)
                .orderBy(fCreatedAt.desc())
                .limit(limit)
                .offset(offset)
                .fetch { record ->
                    val locationJson = record.get(fLocation)?.data()
                    val evidenceJson = record.get(fEvidence)?.data()
                    val evidenceMap = objectMapper.readMapOrEmpty(evidenceJson)
                    Finding(
                        findingId = record.get(fFindingId),
                        tenantId = record.get(fTenant),
                        taskId = record.get(fTaskId),
                        projectId = record.get(fProjectId),
                        sourceEngine = record.get(fSourceEngine),
                        ruleId = record.get(fRuleId),
                        location = objectMapper.readMapOrEmpty(locationJson),
                        evidence = evidenceMap.takeIf { it.isNotEmpty() },
                        severity = Severity.valueOf(record.get(fSeverity)),
                        fingerprint = record.get(fFingerprint),
                        status = FindingStatus.valueOf(record.get(fStatus)),
                        introducedBy = record.get(fIntroducedBy),
                        fixVersion = record.get(fFixVersion),
                        exploitMaturity = record.get(fExploit),
                        createdAt = record.get(fCreatedAt),
                        updatedAt = record.get(fUpdatedAt),
                    )
                }

        val total =
            dsl
                .selectCount()
                .from(findingTable)
                .join(taskTable)
                .on(fTaskId.eq(tTaskId))
                .where(condition)
                .fetchOne(0, Long::class.javaObjectType)
                ?: 0L

        return records to total
    }

    fun listByRepoPaged(
        tenantId: UUID,
        repoId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): Pair<List<Finding>, Long> {
        val findingTable = DSL.table(DSL.name("finding")).`as`("f")
        val taskTable = DSL.table(DSL.name("task")).`as`("t")
        val fFindingId = DSL.field(DSL.name("f", "finding_id"), UUID::class.java)
        val fTenant = DSL.field(DSL.name("f", "tenant_id"), UUID::class.java)
        val fTaskId = DSL.field(DSL.name("f", "task_id"), UUID::class.java)
        val fProjectId = DSL.field(DSL.name("f", "project_id"), UUID::class.java)
        val fSourceEngine = DSL.field(DSL.name("f", "source_engine"), String::class.java)
        val fRuleId = DSL.field(DSL.name("f", "rule_id"), String::class.java)
        val fLocation = DSL.field(DSL.name("f", "location"), JSONB::class.java)
        val fEvidence = DSL.field(DSL.name("f", "evidence"), JSONB::class.java)
        val fSeverity = DSL.field(DSL.name("f", "severity"), String::class.java)
        val fFingerprint = DSL.field(DSL.name("f", "fingerprint"), String::class.java)
        val fStatus = DSL.field(DSL.name("f", "status"), String::class.java)
        val fIntroducedBy = DSL.field(DSL.name("f", "introduced_by"), String::class.java)
        val fFixVersion = DSL.field(DSL.name("f", "fix_version"), String::class.java)
        val fExploit = DSL.field(DSL.name("f", "exploit_maturity"), String::class.java)
        val fCreatedAt = DSL.field(DSL.name("f", "created_at"), OffsetDateTime::class.java)
        val fUpdatedAt = DSL.field(DSL.name("f", "updated_at"), OffsetDateTime::class.java)
        val fDeletedAt = DSL.field(DSL.name("f", "deleted_at"), OffsetDateTime::class.java)
        val tTaskId = DSL.field(DSL.name("t", "task_id"), UUID::class.java)
        val tTenant = DSL.field(DSL.name("t", "tenant_id"), UUID::class.java)
        val tRepoId = DSL.field(DSL.name("t", "repo_id"), UUID::class.java)
        val tType = DSL.field(DSL.name("t", "type"), String::class.java)
        val tDeleted = DSL.field(DSL.name("t", "deleted_at"), OffsetDateTime::class.java)

        val condition =
            fTenant
                .eq(tenantId)
                .and(tTenant.eq(tenantId))
                .and(tRepoId.eq(repoId))
                .and(fDeletedAt.isNull)
                .and(tDeleted.isNull)
                .and(tType.notIn(TaskType.SCA_CHECK.name, TaskType.SUPPLY_CHAIN.name))
                .let { base ->
                    var cond: Condition = base
                    status?.let { cond = cond.and(fStatus.eq(it.name)) }
                    severity?.let { cond = cond.and(fSeverity.eq(it.name)) }
                    search?.takeIf { it.isNotBlank() }?.let { term ->
                        val like = "%${term.lowercase()}%"
                        val ruleLower = DSL.lower(fRuleId)
                        val engineLower = DSL.lower(fSourceEngine)
                        val introducedLower = DSL.lower(fIntroducedBy)
                        val locationLower = DSL.lower(fLocation.cast(String::class.java))
                        cond =
                            cond.and(
                                ruleLower
                                    .like(like)
                                    .or(engineLower.like(like))
                                    .or(introducedLower.like(like))
                                    .or(locationLower.like(like)),
                            )
                    }
                    cond
                }

        val records =
            dsl
                .select(
                    fFindingId,
                    fTenant,
                    fTaskId,
                    fProjectId,
                    fSourceEngine,
                    fRuleId,
                    fLocation,
                    fEvidence,
                    fSeverity,
                    fFingerprint,
                    fStatus,
                    fIntroducedBy,
                    fFixVersion,
                    fExploit,
                    fCreatedAt,
                    fUpdatedAt
                ).from(findingTable)
                .join(taskTable)
                .on(fTaskId.eq(tTaskId))
                .where(condition)
                .orderBy(fCreatedAt.desc())
                .limit(limit)
                .offset(offset)
                .fetch { record ->
                    val locationJson = record.get(fLocation)?.data()
                    val evidenceJson = record.get(fEvidence)?.data()
                    val evidenceMap = objectMapper.readMapOrEmpty(evidenceJson)
                    Finding(
                        findingId = record.get(fFindingId),
                        tenantId = record.get(fTenant),
                        taskId = record.get(fTaskId),
                        projectId = record.get(fProjectId),
                        sourceEngine = record.get(fSourceEngine),
                        ruleId = record.get(fRuleId),
                        location = objectMapper.readMapOrEmpty(locationJson),
                        evidence = evidenceMap.takeIf { it.isNotEmpty() },
                        severity = Severity.valueOf(record.get(fSeverity)),
                        fingerprint = record.get(fFingerprint),
                        status = FindingStatus.valueOf(record.get(fStatus)),
                        introducedBy = record.get(fIntroducedBy),
                        fixVersion = record.get(fFixVersion),
                        exploitMaturity = record.get(fExploit),
                        createdAt = record.get(fCreatedAt),
                        updatedAt = record.get(fUpdatedAt),
                    )
                }

        val total =
            dsl
                .selectCount()
                .from(findingTable)
                .join(taskTable)
                .on(fTaskId.eq(tTaskId))
                .where(condition)
                .fetchOne(0, Long::class.javaObjectType)
                ?: 0L

        return records to total
    }

    private fun <T> listFindingIdsFilteredByTaskType(
        tenantId: UUID,
        fromTable: org.jooq.Table<*>,
        filterField: org.jooq.Field<T>,
        filterValue: T
    ): List<UUID> {
        val taskTable = DSL.table(DSL.name("task")).`as`("t")
        val fId = DSL.field(DSL.name("f", "finding_id"), UUID::class.java)
        val fTaskId = DSL.field(DSL.name("f", "task_id"), UUID::class.java)
        val fTenant = DSL.field(DSL.name("f", "tenant_id"), UUID::class.java)
        val fDeleted = DSL.field(DSL.name("f", "deleted_at"), OffsetDateTime::class.java)
        val tTaskId = DSL.field(DSL.name("t", "task_id"), UUID::class.java)
        val tTenant = DSL.field(DSL.name("t", "tenant_id"), UUID::class.java)
        val tType = DSL.field(DSL.name("t", "type"), String::class.java)
        val tDeleted = DSL.field(DSL.name("t", "deleted_at"), OffsetDateTime::class.java)
        return dsl
            .select(fId)
            .from(fromTable)
            .join(taskTable)
            .on(fTaskId.eq(tTaskId))
            .where(fTenant.eq(tenantId))
            .and(tTenant.eq(tenantId))
            .and(filterField.eq(filterValue))
            .and(fDeleted.isNull)
            .and(tDeleted.isNull)
            .and(tType.notIn(TaskType.SCA_CHECK.name, TaskType.SUPPLY_CHAIN.name))
            .fetch(fId)
    }

    private fun mapFinding(record: Record): Finding {
        val locationJson = record.get(locationField)?.data()
        val evidenceJson = record.get(evidenceField)?.data()
        val evidenceMap = objectMapper.readMapOrEmpty(evidenceJson)
        return Finding(
            findingId = record.get(findingIdField),
            tenantId = record.get(tenantField),
            taskId = record.get(taskField),
            projectId = record.get(projectField),
            sourceEngine = record.get(sourceEngineField),
            ruleId = record.get(ruleField),
            location = objectMapper.readMapOrEmpty(locationJson),
            evidence = evidenceMap.takeIf { it.isNotEmpty() },
            severity = Severity.valueOf(record.get(severityField)),
            fingerprint = record.get(fingerprintField),
            status = FindingStatus.valueOf(record.get(statusField)),
            introducedBy = record.get(introducedByField),
            fixVersion = record.get(fixVersionField),
            exploitMaturity = record.get(exploitField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField),
        )
    }

    private fun activeCondition(
        tenantId: UUID,
        taskId: UUID,
    ): Condition =
        tenantField
            .eq(tenantId)
            .and(taskField.eq(taskId))
            .and(deletedFlag)
}
