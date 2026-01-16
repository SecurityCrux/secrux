package com.secrux.repo

import com.secrux.domain.Ruleset
import com.secrux.domain.RulesetItem
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class RulesetRepository(
    private val dsl: DSLContext,
    private val clock: Clock
) {

    private val rulesetTable = DSL.table("ruleset")
    private val rulesetItemTable = DSL.table("ruleset_item")

    private val rulesetIdField = DSL.field("ruleset_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val profileField = DSL.field("profile", String::class.java)
    private val versionField = DSL.field("version", String::class.java)
    private val sourceField = DSL.field("source", String::class.java)
    private val langsField = DSL.field("langs", Array<String>::class.java)
    private val hashField = DSL.field("hash", String::class.java)
    private val uriField = DSL.field("uri", String::class.java)
    private val signatureField = DSL.field("signature", String::class.java)
    private val createdField = DSL.field("created_at", OffsetDateTime::class.java)
    private val deletedField = DSL.field("deleted_at", OffsetDateTime::class.java)

    private val itemIdField = DSL.field("id", UUID::class.java)
    private val itemRulesetField = DSL.field("ruleset_id", UUID::class.java)
    private val itemTenantField = DSL.field("tenant_id", UUID::class.java)
    private val itemRuleField = DSL.field("rule_id", UUID::class.java)
    private val engineField = DSL.field("engine", String::class.java)
    private val severityField = DSL.field("severity", String::class.java)
    private val enabledField = DSL.field("enabled", Boolean::class.javaObjectType)
    private val ruleHashField = DSL.field("rule_hash", String::class.java)

    fun insert(ruleset: Ruleset, items: List<RulesetItem>) {
        val now = OffsetDateTime.now(clock)
        dsl.transaction { config ->
            val ctx = DSL.using(config)
            ctx.insertInto(rulesetTable)
                .set(rulesetIdField, ruleset.rulesetId)
                .set(tenantField, ruleset.tenantId)
                .set(profileField, ruleset.profile)
                .set(versionField, ruleset.version)
                .set(sourceField, ruleset.source)
                .set(langsField, ruleset.langs.toTypedArray())
                .set(hashField, ruleset.hash)
                .set(uriField, ruleset.uri)
                .set(signatureField, ruleset.signature)
                .set(createdField, now)
                .set(deletedField, ruleset.deletedAt)
                .execute()

            if (items.isNotEmpty()) {
                ctx.batch(items.map { item ->
                    ctx.insertInto(rulesetItemTable)
                        .columns(itemIdField, tenantField, itemRulesetField, itemRuleField, engineField, severityField, enabledField, ruleHashField)
                        .values(
                            item.id,
                            ruleset.tenantId,
                            item.rulesetId,
                            item.ruleId,
                            item.engine,
                            item.severity.name,
                            item.enabled,
                            item.ruleHash
                        )
                }).execute()
            }
        }
    }

    fun list(tenantId: UUID): List<Ruleset> {
        return dsl.selectFrom(rulesetTable)
            .where(tenantField.eq(tenantId))
            .and(deletedField.isNull)
            .orderBy(createdField.desc())
            .fetch { mapRuleset(it) }
    }

    fun findById(rulesetId: UUID, tenantId: UUID): Ruleset? =
        dsl.selectFrom(rulesetTable)
            .where(rulesetIdField.eq(rulesetId))
            .and(tenantField.eq(tenantId))
            .and(deletedField.isNull)
            .fetchOne { mapRuleset(it) }

    fun softDelete(rulesetId: UUID, tenantId: UUID, deletedAt: OffsetDateTime) {
        dsl.update(rulesetTable)
            .set(deletedField, deletedAt)
            .where(rulesetIdField.eq(rulesetId))
            .and(tenantField.eq(tenantId))
            .execute()
    }

    private fun mapRuleset(record: Record): Ruleset {
        return Ruleset(
            rulesetId = record.get(rulesetIdField),
            tenantId = record.get(tenantField),
            source = record.get(sourceField),
            version = record.get(versionField),
            profile = record.get(profileField),
            langs = record.get(langsField)?.toList() ?: emptyList(),
            hash = record.get(hashField),
            signature = record.get(signatureField),
            uri = record.get(uriField),
            deletedAt = record.get(deletedField)
        )
    }

    fun findLatestByProfile(tenantId: UUID, profile: String): Ruleset? {
        return dsl.selectFrom(rulesetTable)
            .where(tenantField.eq(tenantId))
            .and(profileField.eq(profile))
            .and(deletedField.isNull)
            .orderBy(createdField.desc())
            .limit(1)
            .fetchOne { mapRuleset(it) }
    }
}
