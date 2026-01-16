package com.secrux.repo

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.TicketProviderConfig
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class TicketProviderConfigRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val issueTypeNamesType = object : TypeReference<Map<String, String>>() {}

    private val table = DSL.table("ticket_provider_config")
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val providerField = DSL.field("provider", String::class.java)
    private val baseUrlField = DSL.field("base_url", String::class.java)
    private val projectKeyField = DSL.field("project_key", String::class.java)
    private val emailField = DSL.field("email", String::class.java)
    private val tokenCipherField = DSL.field("api_token_cipher", String::class.java)
    private val issueTypeNamesField = DSL.field("issue_type_names", JSONB::class.java)
    private val enabledField = DSL.field("enabled", Boolean::class.javaObjectType)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)

    fun list(tenantId: UUID): List<TicketProviderConfig> =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .orderBy(providerField.asc())
            .fetch { mapConfig(it) }

    fun findByProvider(tenantId: UUID, provider: String): TicketProviderConfig? =
        dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(providerField.eq(provider))
            .fetchOne { mapConfig(it) }

    fun insert(config: TicketProviderConfig) {
        dsl.insertInto(table)
            .set(tenantField, config.tenantId)
            .set(providerField, config.provider)
            .set(baseUrlField, config.baseUrl)
            .set(projectKeyField, config.projectKey)
            .set(emailField, config.email)
            .set(tokenCipherField, config.apiTokenCipher)
            .set(issueTypeNamesField, objectMapper.toJsonb(config.issueTypeNames))
            .set(enabledField, config.enabled)
            .set(createdField, config.createdAt)
            .set(updatedField, config.updatedAt)
            .execute()
    }

    fun update(config: TicketProviderConfig) {
        dsl.update(table)
            .set(baseUrlField, config.baseUrl)
            .set(projectKeyField, config.projectKey)
            .set(emailField, config.email)
            .set(tokenCipherField, config.apiTokenCipher)
            .set(issueTypeNamesField, objectMapper.toJsonb(config.issueTypeNames))
            .set(enabledField, config.enabled)
            .set(updatedField, config.updatedAt)
            .where(tenantField.eq(config.tenantId))
            .and(providerField.eq(config.provider))
            .execute()
    }

    fun delete(tenantId: UUID, provider: String) {
        dsl.deleteFrom(table)
            .where(tenantField.eq(tenantId))
            .and(providerField.eq(provider))
            .execute()
    }

    private fun mapConfig(record: Record): TicketProviderConfig {
        val rawIssueTypeNames = record.get(issueTypeNamesField)?.data() ?: "{}"
        val issueTypeNames =
            runCatching {
                objectMapper.readValue(rawIssueTypeNames, issueTypeNamesType)
            }.getOrElse { emptyMap() }
        return TicketProviderConfig(
            tenantId = record.get(tenantField),
            provider = record.get(providerField),
            baseUrl = record.get(baseUrlField),
            projectKey = record.get(projectKeyField),
            email = record.get(emailField),
            apiTokenCipher = record.get(tokenCipherField),
            issueTypeNames = issueTypeNames,
            enabled = record.get(enabledField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField)
        )
    }
}
