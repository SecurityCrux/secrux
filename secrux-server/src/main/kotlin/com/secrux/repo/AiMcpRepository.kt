package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.AiMcpConfig
import java.time.OffsetDateTime
import java.util.UUID
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Repository

@Repository
class AiMcpRepository(
    @Qualifier("aiDslContext") private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val table = DSL.table("ai_mcp_config")
    private val profileIdField = DSL.field("profile_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val typeField = DSL.field("type", String::class.java)
    private val endpointField = DSL.field("endpoint", String::class.java)
    private val entrypointField = DSL.field("entrypoint", String::class.java)
    private val paramsField = DSL.field("params", JSONB::class.java)
    private val enabledField = DSL.field("enabled", Boolean::class.javaObjectType)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun list(tenantId: UUID): List<AiMcpConfig> =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .orderBy(createdField.desc())
            .fetch { map(it) }

    fun insert(config: AiMcpConfig) {
        dsl.insertInto(table)
            .set(profileIdField, config.profileId)
            .set(tenantField, config.tenantId)
            .set(nameField, config.name)
            .set(typeField, config.type)
            .set(endpointField, config.endpoint)
            .set(entrypointField, config.entrypoint)
            .set(paramsField, objectMapper.toJsonb(config.params))
            .set(enabledField, config.enabled)
            .set(createdField, config.createdAt)
            .set(updatedField, config.updatedAt)
            .execute()
    }

    fun update(config: AiMcpConfig) {
        dsl.update(table)
            .set(nameField, config.name)
            .set(typeField, config.type)
            .set(endpointField, config.endpoint)
            .set(entrypointField, config.entrypoint)
            .set(paramsField, objectMapper.toJsonb(config.params))
            .set(enabledField, config.enabled)
            .set(updatedField, config.updatedAt)
            .where(profileIdField.eq(config.profileId))
            .and(tenantField.eq(config.tenantId))
            .execute()
    }

    fun delete(profileId: UUID, tenantId: UUID) {
        dsl.deleteFrom(table)
            .where(profileIdField.eq(profileId))
            .and(tenantField.eq(tenantId))
            .execute()
    }

    fun findById(profileId: UUID, tenantId: UUID): AiMcpConfig? =
        dsl.selectFrom(table)
            .where(profileIdField.eq(profileId))
            .and(tenantField.eq(tenantId))
            .fetchOne { map(it) }

    private fun map(record: Record): AiMcpConfig =
        AiMcpConfig(
            profileId = record.get(profileIdField),
            tenantId = record.get(tenantField),
            name = record.get(nameField),
            type = record.get(typeField),
            endpoint = record.get(endpointField),
            entrypoint = record.get(entrypointField),
            params = parseParams(record.get(paramsField)),
            enabled = record.get(enabledField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField)
        )

    private fun parseParams(jsonb: JSONB?): Map<String, Any?> {
        return objectMapper.readMapOrEmpty(jsonb)
    }
}
