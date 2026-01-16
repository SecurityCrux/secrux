package com.secrux.repo

import com.secrux.domain.RuleGroup
import com.secrux.domain.RuleGroupMember
import com.secrux.domain.Severity
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class RuleGroupRepository(
    private val dsl: DSLContext
) {

    private val groupTable = DSL.table("rule_group")
    private val memberTable = DSL.table("rule_group_member")

    // group fields
    private val groupIdField = DSL.field("group_id", UUID::class.java)
    private val tenantField = DSL.field("tenant_id", UUID::class.java)
    private val keyField = DSL.field("key", String::class.java)
    private val nameField = DSL.field("name", String::class.java)
    private val descriptionField = DSL.field("description", String::class.java)
    private val groupCreatedField = DSL.field("created_at", OffsetDateTime::class.java)
    private val groupUpdatedField = DSL.field("updated_at", OffsetDateTime::class.java)
    private val groupDeletedField = DSL.field("deleted_at", OffsetDateTime::class.java)

    // member fields
    private val memberIdField = DSL.field("id", UUID::class.java)
    private val memberGroupField = DSL.field("group_id", UUID::class.java)
    private val memberRuleField = DSL.field("rule_id", UUID::class.java)
    private val overrideEnabledField = DSL.field("override_enabled", Boolean::class.javaObjectType)
    private val overrideSeverityField = DSL.field("override_severity", String::class.java)
    private val memberCreatedField = DSL.field("created_at", OffsetDateTime::class.java)

    fun insertGroup(group: RuleGroup) {
        dsl.insertInto(groupTable)
            .set(groupIdField, group.groupId)
            .set(tenantField, group.tenantId)
            .set(keyField, group.key)
            .set(nameField, group.name)
            .set(descriptionField, group.description)
            .set(groupCreatedField, group.createdAt)
            .set(groupUpdatedField, group.updatedAt)
            .set(groupDeletedField, group.deletedAt)
            .execute()
    }

    fun listGroups(tenantId: UUID): List<RuleGroup> =
        dsl.selectFrom(groupTable)
            .where(tenantField.eq(tenantId))
            .and(groupDeletedField.isNull)
            .orderBy(groupCreatedField.desc())
            .fetch { mapGroup(it) }

    fun findGroup(groupId: UUID, tenantId: UUID): RuleGroup? =
        dsl.selectFrom(groupTable)
            .where(groupIdField.eq(groupId))
            .and(tenantField.eq(tenantId))
            .and(groupDeletedField.isNull)
            .fetchOne { mapGroup(it) }

    fun softDeleteGroup(groupId: UUID, tenantId: UUID, deletedAt: OffsetDateTime) {
        dsl.update(groupTable)
            .set(groupDeletedField, deletedAt)
            .where(groupIdField.eq(groupId))
            .and(tenantField.eq(tenantId))
            .execute()
    }

    fun insertMember(member: RuleGroupMember) {
        dsl.insertInto(memberTable)
            .set(memberIdField, member.id)
            .set(tenantField, member.tenantId)
            .set(memberGroupField, member.groupId)
            .set(memberRuleField, member.ruleId)
            .set(overrideEnabledField, member.overrideEnabled)
            .set(overrideSeverityField, member.overrideSeverity?.name)
            .set(memberCreatedField, member.createdAt)
            .execute()
    }

    fun listMembers(groupId: UUID, tenantId: UUID): List<RuleGroupMember> {
        return dsl.selectFrom(memberTable)
            .where(memberGroupField.eq(groupId))
            .and(tenantField.eq(tenantId))
            .fetch { mapMember(it) }
    }

    fun deleteMember(groupId: UUID, memberId: UUID, tenantId: UUID): Int {
        return dsl.deleteFrom(memberTable)
            .where(memberIdField.eq(memberId))
            .and(memberGroupField.eq(groupId))
            .and(tenantField.eq(tenantId))
            .execute()
    }

    private fun mapGroup(record: Record): RuleGroup {
        return RuleGroup(
            groupId = record.get(groupIdField),
            tenantId = record.get(tenantField),
            key = record.get(keyField),
            name = record.get(nameField),
            description = record.get(descriptionField),
            createdAt = record.get(groupCreatedField),
            updatedAt = record.get(groupUpdatedField),
            deletedAt = record.get(groupDeletedField)
        )
    }

    private fun mapMember(record: Record): RuleGroupMember {
        return RuleGroupMember(
            id = record.get(memberIdField),
            tenantId = record.get(tenantField),
            groupId = record.get(memberGroupField),
            ruleId = record.get(memberRuleField),
            overrideEnabled = record.get(overrideEnabledField),
            overrideSeverity = record.get(overrideSeverityField)?.let { Severity.valueOf(it) },
            createdAt = record.get(memberCreatedField)
        )
    }
}

