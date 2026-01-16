package com.secrux.repo

import com.secrux.domain.AiClientConfig
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.beans.factory.annotation.Qualifier

@Repository
class AiClientConfigRepository(
    @Qualifier("aiDslContext") private val dsl: DSLContext
) {

    private val table = DSL.table("ai_client_config")
    private val configIdField = DSL.field("config_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val providerField = DSL.field("provider", String::class.java)
    private val baseUrlField = DSL.field("base_url", String::class.java)
    private val apiKeyField = DSL.field("api_key", String::class.java)
    private val modelField = DSL.field("model", String::class.java)
    private val defaultField = DSL.field("is_default", Boolean::class.javaObjectType)
    private val enabledField = DSL.field("enabled", Boolean::class.javaObjectType)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun list(tenantId: UUID): List<AiClientConfig> =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .orderBy(defaultField.desc(), createdField.desc())
            .fetch { mapConfig(it) }

    fun insert(config: AiClientConfig) {
        dsl.insertInto(table)
            .set(configIdField, config.configId)
            .set(tenantField, config.tenantId)
            .set(nameField, config.name)
            .set(providerField, config.provider)
            .set(baseUrlField, config.baseUrl)
            .set(apiKeyField, config.apiKey)
            .set(modelField, config.model)
            .set(defaultField, config.isDefault)
            .set(enabledField, config.enabled)
            .set(createdField, config.createdAt)
            .set(updatedField, config.updatedAt)
            .execute()
    }

    fun update(config: AiClientConfig) {
        dsl.update(table)
            .set(nameField, config.name)
            .set(providerField, config.provider)
            .set(baseUrlField, config.baseUrl)
            .set(apiKeyField, config.apiKey)
            .set(modelField, config.model)
            .set(defaultField, config.isDefault)
            .set(enabledField, config.enabled)
            .set(updatedField, config.updatedAt)
            .where(configIdField.eq(config.configId))
            .and(tenantField.eq(config.tenantId))
            .execute()
    }

    fun delete(configId: UUID, tenantId: UUID) {
        dsl.deleteFrom(table)
            .where(configIdField.eq(configId))
            .and(tenantField.eq(tenantId))
            .execute()
    }

    fun findById(configId: UUID, tenantId: UUID): AiClientConfig? =
        dsl.selectFrom(table)
            .where(configIdField.eq(configId))
            .and(tenantField.eq(tenantId))
            .fetchOne { mapConfig(it) }

    fun clearDefault(tenantId: UUID) {
        dsl.update(table)
            .set(defaultField, false)
            .where(tenantField.eq(tenantId))
            .and(defaultField.eq(true))
            .execute()
    }

    fun findDefaultEnabled(tenantId: UUID): AiClientConfig? =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(enabledField.eq(true))
            .orderBy(defaultField.desc(), createdField.desc())
            .limit(1)
            .fetchOne { mapConfig(it) }

    private fun mapConfig(record: Record): AiClientConfig =
        AiClientConfig(
            configId = record.get(configIdField),
            tenantId = record.get(tenantField),
            name = record.get(nameField),
            provider = record.get(providerField),
            baseUrl = record.get(baseUrlField),
            apiKey = record.get(apiKeyField),
            model = record.get(modelField),
            isDefault = record.get(defaultField),
            enabled = record.get(enabledField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField)
        )
}
