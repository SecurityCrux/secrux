package com.secrux.repo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.Executor
import com.secrux.domain.ExecutorStatus
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class ExecutorRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val labelsType = object : TypeReference<Map<String, String>>() {}

    private val table = DSL.table("executor")
    private val executorIdField = DSL.field("executor_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val statusField = DSL.field("status", String::class.java)
    private val labelsField = DSL.field("labels", JSONB::class.java)
    private val cpuCapacityField = DSL.field("cpu_capacity", Int::class.javaObjectType)
    private val memoryCapacityField = DSL.field("memory_capacity_mb", Int::class.javaObjectType)
    private val cpuUsageField = DSL.field("cpu_usage", Float::class.javaObjectType)
    private val memoryUsageField = DSL.field("memory_usage_mb", Int::class.javaObjectType)
    private val heartbeatField = DSL.field("last_heartbeat", OffsetDateTime::class.java)
    private val tokenField = DSL.field("quic_token", String::class.java)
    private val publicKeyField = DSL.field("public_key", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun insert(executor: Executor) {
        dsl.insertInto(table)
            .set(executorIdField, executor.executorId)
            .set(tenantField, executor.tenantId)
            .set(nameField, executor.name)
            .set(statusField, executor.status.name)
            .set(labelsField, objectMapper.toJsonb(executor.labels))
            .set(cpuCapacityField, executor.cpuCapacity)
            .set(memoryCapacityField, executor.memoryCapacityMb)
            .set(cpuUsageField, executor.cpuUsage)
            .set(memoryUsageField, executor.memoryUsageMb)
            .set(heartbeatField, executor.lastHeartbeat)
            .set(tokenField, executor.quicToken)
            .set(publicKeyField, executor.publicKey)
            .set(createdField, executor.createdAt)
            .set(updatedField, executor.updatedAt)
            .execute()
    }

    fun update(executor: Executor) {
        dsl.update(table)
            .set(nameField, executor.name)
            .set(statusField, executor.status.name)
            .set(labelsField, objectMapper.toJsonb(executor.labels))
            .set(cpuCapacityField, executor.cpuCapacity)
            .set(memoryCapacityField, executor.memoryCapacityMb)
            .set(cpuUsageField, executor.cpuUsage)
            .set(memoryUsageField, executor.memoryUsageMb)
            .set(heartbeatField, executor.lastHeartbeat)
            .set(publicKeyField, executor.publicKey)
            .set(updatedField, executor.updatedAt)
            .where(executorIdField.eq(executor.executorId))
            .and(tenantField.eq(executor.tenantId))
            .execute()
    }

    fun findById(executorId: UUID, tenantId: UUID): Executor? =
        dsl.selectFrom(table)
            .where(executorIdField.eq(executorId))
            .and(tenantField.eq(tenantId))
            .fetchOne { mapExecutor(it) }

    fun findByToken(token: String): Executor? =
        dsl.selectFrom(table)
            .where(tokenField.eq(token))
            .fetchOne { mapExecutor(it) }

    fun list(
        tenantId: UUID,
        status: ExecutorStatus?,
        search: String?
    ): List<Executor> {
        var condition = tenantField.eq(tenantId)
        status?.let { condition = condition.and(statusField.eq(it.name)) }
        search?.takeIf { it.isNotBlank() }?.let { term ->
            val like = "%${term.lowercase()}%"
            condition = condition.and(DSL.lower(nameField).like(like))
        }
        return dsl.selectFrom(table)
            .where(condition)
            .orderBy(createdField.desc())
            .fetch { mapExecutor(it) }
    }

    fun findStaleExecutors(
        deadline: OffsetDateTime,
        statuses: Collection<ExecutorStatus>
    ): List<Executor> {
        if (statuses.isEmpty()) return emptyList()
        return dsl.selectFrom(table)
            .where(statusField.`in`(statuses.map { it.name }))
            .and(heartbeatField.isNull.or(heartbeatField.lt(deadline)))
            .fetch { mapExecutor(it) }
    }

    private fun mapExecutor(record: Record): Executor {
        val labelsJson = record.get(labelsField)?.data() ?: "{}"
        return Executor(
            executorId = record.get(executorIdField),
            tenantId = record.get(tenantField),
            name = record.get(nameField),
            status = ExecutorStatus.valueOf(record.get(statusField)),
            labels = objectMapper.readValue(labelsJson, labelsType),
            cpuCapacity = record.get(cpuCapacityField),
            memoryCapacityMb = record.get(memoryCapacityField),
            cpuUsage = record.get(cpuUsageField),
            memoryUsageMb = record.get(memoryUsageField),
            lastHeartbeat = record.get(heartbeatField),
            quicToken = record.get(tokenField),
            publicKey = record.get(publicKeyField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField)
        )
    }
}
