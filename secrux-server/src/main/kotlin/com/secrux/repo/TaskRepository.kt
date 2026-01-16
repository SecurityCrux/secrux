package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.Task
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TaskRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val taskTable = DSL.table("task")
    private val taskIdField = DSL.field("task_id", UUID::class.java)
    private val tenantIdField = DSL.field("tenant_id", UUID::class.java)
    private val projectIdField = DSL.field("project_id", UUID::class.java)
    private val repoIdField = DSL.field("repo_id", UUID::class.java)
    private val executorIdField = DSL.field("executor_id", UUID::class.java)
    private val typeField = DSL.field("type", String::class.java)
    private val specField = DSL.field("spec", JSONB::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val ownerField = DSL.field("owner", UUID::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val correlationField = DSL.field("correlation_id", String::class.java)
    private val sourceRefTypeField = DSL.field("source_ref_type", String::class.java)
    private val sourceRefField = DSL.field("source_ref", String::class.java)
    private val commitShaField = DSL.field("commit_sha", String::class.java)
    private val engineField = DSL.field("engine", String::class.java)
    private val createdAtField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedAtField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val semgrepProEnabledField = DSL.field("semgrep_pro_enabled", Boolean::class.javaObjectType)
    private val semgrepTokenCipherField = DSL.field("semgrep_token_cipher", String::class.java)
    private val semgrepTokenExpiresField = DSL.field("semgrep_token_expires_at", OffsetDateTime::class.java)
    private val deletedAtField = DSL.field("deleted_at", OffsetDateTime::class.java)

    fun insert(task: Task) {
        dsl.insertInto(taskTable)
            .set(taskIdField, task.taskId)
            .set(tenantIdField, task.tenantId)
            .set(projectIdField, task.projectId)
            .set(repoIdField, task.repoId)
            .set(executorIdField, task.executorId)
            .set(typeField, task.type.name)
            .set(specField, objectMapper.toJsonb(task.spec))
            .set(statusField, task.status.name)
            .set(ownerField, task.owner)
            .set(nameField, task.name)
            .set(correlationField, task.correlationId)
            .set(sourceRefTypeField, task.sourceRefType.name)
            .set(sourceRefField, task.sourceRef)
            .set(commitShaField, task.commitSha)
            .set(engineField, task.engine)
            .set(semgrepProEnabledField, task.semgrepProEnabled)
            .set(semgrepTokenCipherField, task.semgrepTokenCipher)
            .set(semgrepTokenExpiresField, task.semgrepTokenExpiresAt)
            .execute()
    }

    fun updateStatus(taskId: UUID, tenantId: UUID, status: String) {
        dsl.update(taskTable)
            .set(statusField, status)
            .set(updatedAtField, DSL.currentOffsetDateTime())
            .where(taskIdField.eq(taskId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }

    fun findById(taskId: UUID, tenantId: UUID): Task? {
        return dsl.selectFrom(taskTable)
            .where(taskIdField.eq(taskId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .fetchOne { mapTask(it) }
    }

    fun find(taskId: UUID): Task? {
        return dsl.selectFrom(taskTable)
            .where(taskIdField.eq(taskId))
            .and(deletedAtField.isNull)
            .fetchOne { mapTask(it) }
    }

    fun list(
        tenantId: UUID,
        projectId: UUID? = null,
        type: com.secrux.domain.TaskType? = null,
        excludeType: com.secrux.domain.TaskType? = null,
        status: com.secrux.domain.TaskStatus? = null,
        search: String? = null,
        limit: Int,
        offset: Int
    ): Pair<List<Task>, Long> {
        val condition = buildFilterCondition(tenantId, projectId, type, excludeType, status, search)
        val records =
            dsl.selectFrom(taskTable)
                .where(condition)
                .orderBy(createdAtField.desc())
                .limit(limit)
                .offset(offset)
                .fetch { mapTask(it) }
        val total =
            dsl.selectCount()
                .from(taskTable)
                .where(condition)
                .fetchOne(0, Long::class.javaObjectType)
                ?: 0L
        return records to total
    }

    fun findByCorrelation(tenantId: UUID, correlationId: String): Task? {
        return dsl.selectFrom(taskTable)
            .where(tenantIdField.eq(tenantId))
            .and(correlationField.eq(correlationId))
            .and(deletedAtField.isNull)
            .fetchOne { mapTask(it) }
    }

    fun update(task: Task) {
        dsl.update(taskTable)
            .set(projectIdField, task.projectId)
            .set(repoIdField, task.repoId)
            .set(executorIdField, task.executorId)
            .set(typeField, task.type.name)
            .set(specField, objectMapper.toJsonb(task.spec))
            .set(ownerField, task.owner)
            .set(nameField, task.name)
            .set(correlationField, task.correlationId)
            .set(sourceRefTypeField, task.sourceRefType.name)
            .set(sourceRefField, task.sourceRef)
            .set(commitShaField, task.commitSha)
            .set(engineField, task.engine)
            .set(semgrepProEnabledField, task.semgrepProEnabled)
            .set(semgrepTokenCipherField, task.semgrepTokenCipher)
            .set(semgrepTokenExpiresField, task.semgrepTokenExpiresAt)
            .set(updatedAtField, task.updatedAt)
            .where(taskIdField.eq(task.taskId))
            .and(tenantIdField.eq(task.tenantId))
            .and(deletedAtField.isNull)
            .execute()
    }

    private fun mapTask(record: Record): Task {
        val specJson = record.get(specField)
        val spec = objectMapper.readValue(specJson.data(), com.secrux.domain.TaskSpec::class.java)
        return Task(
            taskId = record.get(taskIdField),
            tenantId = record.get(tenantIdField),
            projectId = record.get(projectIdField),
            repoId = record.get(repoIdField),
            executorId = record.get(executorIdField),
            type = com.secrux.domain.TaskType.valueOf(record.get(typeField)),
            spec = spec,
            status = com.secrux.domain.TaskStatus.valueOf(record.get(statusField)),
            owner = record.get(ownerField),
            name = record.get(nameField),
            correlationId = record.get(correlationField),
            sourceRefType = com.secrux.domain.SourceRefType.valueOf(record.get(sourceRefTypeField)),
            sourceRef = record.get(sourceRefField),
            commitSha = record.get(commitShaField),
            engine = record.get(engineField),
            semgrepProEnabled = record.get(semgrepProEnabledField) ?: false,
            semgrepTokenCipher = record.get(semgrepTokenCipherField),
            semgrepTokenExpiresAt = record.get(semgrepTokenExpiresField),
            createdAt = record.get(createdAtField),
            updatedAt = record.get(updatedAtField)
        )
    }

    fun clearSemgrepToken(taskId: UUID) {
        dsl.update(taskTable)
            .set(semgrepTokenCipherField, null as String?)
            .set(semgrepTokenExpiresField, null as OffsetDateTime?)
            .where(taskIdField.eq(taskId))
            .and(deletedAtField.isNull)
            .execute()
    }

    fun clearExpiredSemgrepTokens(cutoff: OffsetDateTime) {
        dsl.update(taskTable)
            .set(semgrepTokenCipherField, null as String?)
            .set(semgrepTokenExpiresField, null as OffsetDateTime?)
            .where(semgrepTokenExpiresField.isNotNull)
            .and(semgrepTokenExpiresField.lt(cutoff))
            .and(deletedAtField.isNull)
            .execute()
    }

    fun softDelete(taskId: UUID, tenantId: UUID): Boolean =
        dsl.update(taskTable)
            .set(deletedAtField, DSL.currentOffsetDateTime())
            .where(taskIdField.eq(taskId))
            .and(tenantIdField.eq(tenantId))
            .and(deletedAtField.isNull)
            .execute() > 0

    private fun buildFilterCondition(
        tenantId: UUID,
        projectId: UUID?,
        type: com.secrux.domain.TaskType?,
        excludeType: com.secrux.domain.TaskType?,
        status: com.secrux.domain.TaskStatus?,
        search: String?
    ): Condition {
        var condition: Condition = tenantIdField.eq(tenantId)
        projectId?.let { condition = condition.and(projectIdField.eq(it)) }
        type?.let { condition = condition.and(typeField.eq(it.name)) }
        excludeType?.let { condition = condition.and(typeField.ne(it.name)) }
        status?.let { condition = condition.and(statusField.eq(it.name)) }
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val like = "%${term.lowercase()}%"
            val correlationLower = DSL.lower(correlationField)
            val nameLower = DSL.lower(nameField)
            val sourceRefLower = DSL.lower(sourceRefField)
            val commitLower = DSL.lower(commitShaField)
            val taskIdLower = DSL.lower(taskIdField.cast(String::class.java))
            condition =
                condition.and(
                    nameLower.like(like)
                        .or(correlationLower.like(like))
                        .or(sourceRefLower.like(like))
                        .or(commitLower.like(like))
                        .or(taskIdLower.like(like))
                )
        }
        return condition.and(deletedAtField.isNull)
    }
}
