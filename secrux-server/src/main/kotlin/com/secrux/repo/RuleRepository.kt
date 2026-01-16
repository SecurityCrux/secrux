package com.secrux.repo

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.domain.Rule
import com.secrux.domain.Severity
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class RuleRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper
) {

    private val table = DSL.table("rule")
    private val ruleIdField = DSL.field("rule_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val scopeField = DSL.field("scope", String::class.java)
    private val keyField = DSL.field("key", String::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val engineField = DSL.field("engine", String::class.java)
    private val langsField = DSL.field("langs", Array<String>::class.java)
    private val severityField = DSL.field("severity_default", String::class.java)
    private val tagsField = DSL.field("tags", Array<String>::class.java)
    private val patternField = DSL.field("pattern", JSONB::class.java)
    private val docsField = DSL.field("docs", JSONB::class.java)
    private val enabledField = DSL.field("enabled", Boolean::class.javaObjectType)
    private val hashField = DSL.field("hash", String::class.java)
    private val signatureField = DSL.field("signature", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val updatedField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val deprecatedField = DSL.field("deprecated_at", OffsetDateTime::class.java)

    fun insert(rule: Rule) {
        dsl.insertInto(table)
            .set(ruleIdField, rule.ruleId)
            .set(tenantField, rule.tenantId)
            .set(scopeField, rule.scope)
            .set(keyField, rule.key)
            .set(nameField, rule.name)
            .set(engineField, rule.engine)
            .set(langsField, rule.langs.toTypedArray())
            .set(severityField, rule.severityDefault.name)
            .set(tagsField, rule.tags.toTypedArray())
            .set(patternField, objectMapper.toJsonb(rule.pattern))
            .set(docsField, objectMapper.toJsonbOrNull(rule.docs))
            .set(enabledField, rule.enabled)
            .set(hashField, rule.hash)
            .set(signatureField, rule.signature)
            .set(createdField, rule.createdAt)
            .set(updatedField, rule.updatedAt)
            .set(deprecatedField, rule.deprecatedAt)
            .execute()
    }

    fun list(tenantId: UUID): List<Rule> {
        return dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(deprecatedField.isNull)
            .orderBy(createdField.desc())
            .fetch { mapRule(it) }
    }

    fun findById(ruleId: UUID, tenantId: UUID): Rule? {
        return dsl.selectFrom(table)
            .where(ruleIdField.eq(ruleId))
            .and(tenantField.eq(tenantId))
            .and(deprecatedField.isNull)
            .fetchOne { mapRule(it) }
    }

    fun findByIds(tenantId: UUID, ruleIds: Collection<UUID>): List<Rule> {
        if (ruleIds.isEmpty()) return emptyList()
        return dsl.selectFrom(table)
            .where(tenantField.eq(tenantId))
            .and(ruleIdField.`in`(ruleIds))
            .fetch { mapRule(it) }
    }

    fun update(rule: Rule) {
        dsl.update(table)
            .set(scopeField, rule.scope)
            .set(keyField, rule.key)
            .set(nameField, rule.name)
            .set(engineField, rule.engine)
            .set(langsField, rule.langs.toTypedArray())
            .set(severityField, rule.severityDefault.name)
            .set(tagsField, rule.tags.toTypedArray())
            .set(patternField, objectMapper.toJsonb(rule.pattern))
            .set(docsField, objectMapper.toJsonbOrNull(rule.docs))
            .set(enabledField, rule.enabled)
            .set(hashField, rule.hash)
            .set(signatureField, rule.signature)
            .set(updatedField, rule.updatedAt)
            .where(ruleIdField.eq(rule.ruleId))
            .and(tenantField.eq(rule.tenantId))
            .execute()
    }

    private fun mapRule(record: Record): Rule {
        val langs = record.get(langsField)?.toList() ?: emptyList()
        val tags = record.get(tagsField)?.toList() ?: emptyList()
        val pattern = objectMapper.readMapOrEmpty(record.get(patternField))
        val docs = record.get(docsField)?.let { objectMapper.readMapOrEmpty(it) }
        return Rule(
            ruleId = record.get(ruleIdField),
            tenantId = record.get(tenantField),
            scope = record.get(scopeField),
            key = record.get(keyField),
            name = record.get(nameField),
            engine = record.get(engineField),
            langs = langs,
            severityDefault = Severity.valueOf(record.get(severityField)),
            tags = tags,
            pattern = pattern,
            docs = docs,
            enabled = record.get(enabledField) ?: true,
            hash = record.get(hashField),
            signature = record.get(signatureField),
            createdAt = record.get(createdField),
            updatedAt = record.get(updatedField),
            deprecatedAt = record.get(deprecatedField)
        )
    }
}
