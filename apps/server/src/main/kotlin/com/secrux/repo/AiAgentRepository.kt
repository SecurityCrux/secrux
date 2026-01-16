package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.AiAgentConfig
import java.time.OffsetDateTime
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository

@Repository
class AiAgentRepository(
    @Qualifier("aiDslContext") private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val table = DSL.table("ai_agent_config")
    private val agentIdField = DSL.field("agent_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val kindField = DSL.field("kind", String::class.java)
    private val entrypointField = DSL.field("entrypoint", String::class.java)
    private val paramsField = DSL.field("params", JSONB::class.java)
    private val stageTypesField = DSL.field("stage_types", Array<String>::class.java)
    private val mcpProfileField = DSL.field("mcp_profile_id", UUID::class.java)
    private val enabledField = DSL.field("enabled", Boolean::class.javaObjectType)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun list(tenantId: UUID): List<AiAgentConfig> =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .orderBy(createdField.desc())
            .fetch { map(it) }

    fun insert(config: AiAgentConfig) {
        dsl.insertInto(table)
            .set(agentIdField, config.agentId)
            .set(tenantField, config.tenantId)
            .set(nameField, config.name)
            .set(kindField, config.kind)
            .set(entrypointField, config.entrypoint)
            .set(paramsField, objectMapper.toJsonb(config.params))
            .set(stageTypesField, config.stageTypes.toTypedArray())
            .set(mcpProfileField, config.mcpProfileId)
            .set(enabledField, config.enabled)
            .set(createdField, config.createdAt)
            .set(updatedField, config.updatedAt)
            .execute()
    }

    fun update(config: AiAgentConfig) {
        dsl.update(table)
            .set(nameField, config.name)
            .set(kindField, config.kind)
            .set(entrypointField, config.entrypoint)
            .set(paramsField, objectMapper.toJsonb(config.params))
            .set(stageTypesField, config.stageTypes.toTypedArray())
            .set(mcpProfileField, config.mcpProfileId)
            .set(enabledField, config.enabled)
            .set(updatedField, config.updatedAt)
            .where(agentIdField.eq(config.agentId))
            .and(tenantField.eq(config.tenantId))
            .execute()
    }

    fun delete(agentId: UUID, tenantId: UUID) {
        dsl.deleteFrom(table)
            .where(agentIdField.eq(agentId))
            .and(tenantField.eq(tenantId))
            .execute()
    }

    fun findById(agentId: UUID, tenantId: UUID): AiAgentConfig? =
        dsl.selectFrom(table)
            .where(agentIdField.eq(agentId))
            .and(tenantField.eq(tenantId))
            .fetchOne { map(it) }

    private fun map(record: Record): AiAgentConfig =
        AiAgentConfig(
            agentId = record.get(agentIdField),
            tenantId = record.get(tenantField),
            name = record.get(nameField),
            kind = record.get(kindField),
            entrypoint = record.get(entrypointField),
            params = parseParams(record.get(paramsField)),
            stageTypes = record.get(stageTypesField)?.toList() ?: emptyList(),
            mcpProfileId = record.get(mcpProfileField),
            enabled = record.get(enabledField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField)
        )

    private fun parseParams(jsonb: JSONB?): Map<String, Any?> {
        return objectMapper.readMapOrEmpty(jsonb)
    }
}
