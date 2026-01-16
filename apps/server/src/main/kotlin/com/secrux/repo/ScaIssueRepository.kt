package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.FindingStatus
import com.secrux.domain.ScaIssue
import com.secrux.domain.Severity
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ScaIssueRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    private val table = DSL.table("sca_issue")
    private val issueIdField = DSL.field("issue_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val taskField = DSL.field("task_id", UUID::class.java)
    private val projectField = DSL.field("project_id", UUID::class.java)
    private val sourceEngineField = DSL.field("source_engine", String::class.java)
    private val vulnIdField = DSL.field("vuln_id", String::class.java)
    private val severityField = DSL.field("severity", String::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val packageNameField = DSL.field("package_name", String::class.java)
    private val installedVersionField = DSL.field("installed_version", String::class.java)
    private val fixedVersionField = DSL.field("fixed_version", String::class.java)
    private val primaryUrlField = DSL.field("primary_url", String::class.java)
    private val componentPurlField = DSL.field("component_purl", String::class.java)
    private val componentNameField = DSL.field("component_name", String::class.java)
    private val componentVersionField = DSL.field("component_version", String::class.java)
    private val introducedByField = DSL.field("introduced_by", String::class.java)
    private val locationField = DSL.field("location", JSONB::class.java)
    private val evidenceField = DSL.field("evidence", JSONB::class.java)
    private val issueKeyField = DSL.field("issue_key", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)
    private val deletedFlag = deletedAtField.isNull

    fun listByTask(
        tenantId: UUID,
        taskId: UUID,
    ): List<ScaIssue> =
        dsl
            .selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(taskField.eq(taskId))
            .and(deletedFlag)
            .orderBy(createdField.desc())
            .fetch { mapIssue(it) }

    fun upsertAll(issues: List<ScaIssue>) {
        issues.forEach { issue ->
            dsl
                .insertInto(table)
                .set(issueIdField, issue.issueId)
                .set(tenantField, issue.tenantId)
                .set(taskField, issue.taskId)
                .set(projectField, issue.projectId)
                .set(sourceEngineField, issue.sourceEngine)
                .set(vulnIdField, issue.vulnId)
                .set(severityField, issue.severity.name)
                .set(statusField, issue.status.name)
                .set(packageNameField, issue.packageName)
                .set(installedVersionField, issue.installedVersion)
                .set(fixedVersionField, issue.fixedVersion)
                .set(primaryUrlField, issue.primaryUrl)
                .set(componentPurlField, issue.componentPurl)
                .set(componentNameField, issue.componentName)
                .set(componentVersionField, issue.componentVersion)
                .set(introducedByField, issue.introducedBy)
                .set(locationField, objectMapper.toJsonb(issue.location))
                .set(evidenceField, objectMapper.toJsonb(issue.evidence ?: emptyMap<String, Any?>()))
                .set(issueKeyField, issue.issueKey)
                .set(createdField, issue.createdAt)
                .set(updatedField, issue.updatedAt)
                .onConflict(tenantField, taskField, issueKeyField)
                .doUpdate()
                .set(vulnIdField, issue.vulnId)
                .set(severityField, issue.severity.name)
                .set(packageNameField, issue.packageName)
                .set(installedVersionField, issue.installedVersion)
                .set(fixedVersionField, issue.fixedVersion)
                .set(primaryUrlField, issue.primaryUrl)
                .set(componentPurlField, issue.componentPurl)
                .set(componentNameField, issue.componentName)
                .set(componentVersionField, issue.componentVersion)
                .set(introducedByField, issue.introducedBy)
                .set(locationField, objectMapper.toJsonb(issue.location))
                .set(evidenceField, objectMapper.toJsonb(issue.evidence ?: emptyMap<String, Any?>()))
                .set(updatedField, issue.updatedAt)
                .set(deletedAtField, null as OffsetDateTime?)
                .execute()
        }
    }

    fun listByTaskPaged(
        tenantId: UUID,
        taskId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Pair<List<ScaIssue>, Long> {
        var condition: Condition =
            tenantField
                .eq(tenantId)
                .and(taskField.eq(taskId))
                .and(deletedFlag)
        status?.let { condition = condition.and(statusField.eq(it.name)) }
        severity?.let { condition = condition.and(severityField.eq(it.name)) }
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val like = "%${term.lowercase()}%"
            condition =
                condition.and(
                    DSL.lower(vulnIdField)
                        .like(like)
                        .or(DSL.lower(packageNameField).like(like))
                        .or(DSL.lower(componentPurlField).like(like))
                        .or(DSL.lower(componentNameField).like(like))
                        .or(DSL.lower(installedVersionField).like(like))
                        .or(DSL.lower(locationField.cast(String::class.java)).like(like))
                        .or(DSL.lower(evidenceField.cast(String::class.java)).like(like)),
                )
        }

        val items =
            dsl
                .selectFrom(table)
                .where(condition)
                .orderBy(createdField.desc())
                .limit(limit)
                .offset(offset)
                .fetch { mapIssue(it) }

        val total =
            dsl
                .selectCount()
                .from(table)
                .where(condition)
                .fetchOne(0, Long::class.javaObjectType)
                ?: 0L

        return items to total
    }

    fun findById(
        tenantId: UUID,
        issueId: UUID,
    ): ScaIssue? =
        dsl
            .selectFrom(table)
            .where(issueIdField.eq(issueId))
            .and(tenantField.eq(tenantId))
            .and(deletedFlag)
            .fetchOne { mapIssue(it) }

    fun findByIds(
        tenantId: UUID,
        issueIds: List<UUID>,
    ): List<ScaIssue> {
        val ids = issueIds.distinct()
        if (ids.isEmpty()) return emptyList()
        return dsl
            .selectFrom(table)
            .where(issueIdField.`in`(ids))
            .and(tenantField.eq(tenantId))
            .and(deletedFlag)
            .fetch { mapIssue(it) }
    }

    fun updateStatus(
        tenantId: UUID,
        issueId: UUID,
        status: FindingStatus,
    ) {
        dsl
            .update(table)
            .set(statusField, status.name)
            .set(updatedField, DSL.currentOffsetDateTime())
            .where(issueIdField.eq(issueId))
            .and(tenantField.eq(tenantId))
            .and(deletedFlag)
            .execute()
    }

    fun softDelete(
        tenantId: UUID,
        issueId: UUID,
    ): Boolean =
        dsl
            .update(table)
            .set(deletedAtField, DSL.currentOffsetDateTime())
            .where(issueIdField.eq(issueId))
            .and(tenantField.eq(tenantId))
            .and(deletedFlag)
            .execute() > 0

    private fun mapIssue(record: Record): ScaIssue {
        val locationJson = record.get(locationField)?.data()
        val evidenceJson = record.get(evidenceField)?.data()
        return ScaIssue(
            issueId = record.get(issueIdField),
            tenantId = record.get(tenantField),
            taskId = record.get(taskField),
            projectId = record.get(projectField),
            sourceEngine = record.get(sourceEngineField),
            vulnId = record.get(vulnIdField),
            severity = Severity.valueOf(record.get(severityField)),
            status = FindingStatus.valueOf(record.get(statusField)),
            packageName = record.get(packageNameField),
            installedVersion = record.get(installedVersionField),
            fixedVersion = record.get(fixedVersionField),
            primaryUrl = record.get(primaryUrlField),
            componentPurl = record.get(componentPurlField),
            componentName = record.get(componentNameField),
            componentVersion = record.get(componentVersionField),
            introducedBy = record.get(introducedByField),
            location = objectMapper.readMapOrEmpty(locationJson),
            evidence = objectMapper.readMapOrEmpty(evidenceJson).takeIf { it.isNotEmpty() },
            issueKey = record.get(issueKeyField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField),
        )
    }
}
