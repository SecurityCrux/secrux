package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.Rule
import com.secrux.domain.RuleGroup
import com.secrux.domain.RuleGroupMember
import com.secrux.domain.Ruleset
import com.secrux.domain.RulesetItem
import com.secrux.dto.RuleGroupMemberRequest
import com.secrux.dto.RuleGroupRequest
import com.secrux.dto.RuleGroupResponse
import com.secrux.dto.RuleResponse
import com.secrux.dto.RuleUpsertRequest
import com.secrux.dto.RulesetPublishRequest
import com.secrux.dto.RulesetResponse
import com.secrux.repo.RuleGroupRepository
import com.secrux.repo.RuleRepository
import com.secrux.repo.RulesetRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class RuleService(
    private val ruleRepository: RuleRepository,
    private val ruleGroupRepository: RuleGroupRepository,
    private val rulesetRepository: RulesetRepository,
    private val clock: Clock
) {

    fun createRule(tenantId: UUID, request: RuleUpsertRequest): RuleResponse {
        val now = OffsetDateTime.now(clock)
        val rule = Rule(
            ruleId = UUID.randomUUID(),
            tenantId = tenantId,
            scope = request.scope,
            key = request.key,
            name = request.name,
            engine = request.engine,
            langs = request.langs,
            severityDefault = request.severityDefault,
            tags = request.tags,
            pattern = request.pattern,
            docs = request.docs,
            enabled = request.enabled,
            hash = request.hash,
            signature = request.signature,
            createdAt = now,
            updatedAt = now,
            deprecatedAt = null
        )
        ruleRepository.insert(rule)
        return rule.toResponse()
    }

    fun listRules(tenantId: UUID): List<RuleResponse> =
        ruleRepository.list(tenantId).map { it.toResponse() }

    fun updateRule(
        tenantId: UUID,
        ruleId: UUID,
        request: RuleUpsertRequest
    ): RuleResponse {
        val existing = ruleRepository.findById(ruleId, tenantId)
            ?: throw SecruxException(ErrorCode.RULE_NOT_FOUND, "Rule not found")
        val updated = existing.copy(
            scope = request.scope,
            key = request.key,
            name = request.name,
            engine = request.engine,
            langs = request.langs,
            severityDefault = request.severityDefault,
            tags = request.tags,
            pattern = request.pattern,
            docs = request.docs,
            enabled = request.enabled,
            hash = request.hash,
            signature = request.signature,
            updatedAt = OffsetDateTime.now(clock)
        )
        ruleRepository.update(updated)
        return updated.toResponse()
    }

    fun createRuleGroup(tenantId: UUID, request: RuleGroupRequest): RuleGroupResponse {
        val now = OffsetDateTime.now(clock)
        val group = RuleGroup(
            groupId = UUID.randomUUID(),
            tenantId = tenantId,
            key = request.key,
            name = request.name,
            description = request.description,
            createdAt = now,
            updatedAt = now,
            deletedAt = null
        )
        ruleGroupRepository.insertGroup(group)
        return group.toResponse()
    }

    fun addRuleToGroup(tenantId: UUID, groupId: UUID, request: RuleGroupMemberRequest): RuleGroupResponse {
        val group = ruleGroupRepository.findGroup(groupId, tenantId)
            ?: throw SecruxException(ErrorCode.RULE_GROUP_NOT_FOUND, "Rule group not found")
        val member = RuleGroupMember(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            groupId = groupId,
            ruleId = request.ruleId,
            overrideEnabled = request.overrideEnabled,
            overrideSeverity = request.overrideSeverity,
            createdAt = OffsetDateTime.now(clock)
        )
        ruleGroupRepository.insertMember(member)
        return group.toResponse()
    }

    fun deleteRuleFromGroup(tenantId: UUID, groupId: UUID, memberId: UUID) {
        ruleGroupRepository.findGroup(groupId, tenantId)
            ?: throw SecruxException(ErrorCode.RULE_GROUP_NOT_FOUND, "Rule group not found")
        val affected = ruleGroupRepository.deleteMember(groupId, memberId, tenantId)
        if (affected == 0) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Rule group member not found")
        }
    }

    fun publishRuleset(tenantId: UUID, request: RulesetPublishRequest): RulesetResponse {
        val group = ruleGroupRepository.findGroup(request.groupId, tenantId)
            ?: throw SecruxException(ErrorCode.RULE_GROUP_NOT_FOUND, "Rule group not found")
        val members = ruleGroupRepository.listMembers(request.groupId, tenantId)
        if (members.isEmpty()) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Rule group has no members")
        }
        val rules = ruleRepository.findByIds(tenantId, members.map { it.ruleId })
        if (rules.size != members.size) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Some rules missing for ruleset publish")
        }
        val ruleMap = rules.associateBy { it.ruleId }
        val items = members.map { member ->
            val rule = ruleMap[member.ruleId]
                ?: throw SecruxException(ErrorCode.INTERNAL_ERROR, "Rule missing in repository")
            val severity = member.overrideSeverity ?: rule.severityDefault
            val enabled = member.overrideEnabled ?: rule.enabled
            RulesetItem(
                id = UUID.randomUUID(),
                rulesetId = UUID.randomUUID(), // placeholder update later
                ruleId = rule.ruleId,
                engine = rule.engine,
                severity = severity,
                enabled = enabled,
                ruleHash = rule.hash
            )
        }

        val rulesetId = UUID.randomUUID()
        val version = request.version ?: OffsetDateTime.now(clock).toEpochSecond().toString()
        val langs = rules.flatMap { it.langs }.distinct()
        val hash = digestRules(items)
        val ruleset = Ruleset(
            rulesetId = rulesetId,
            tenantId = tenantId,
            source = "group:${group.key}",
            version = version,
            profile = request.profile,
            langs = langs,
            hash = hash,
            signature = request.signature,
            uri = null,
            deletedAt = null
        )
        val itemsWithId = items.map {
            it.copy(rulesetId = rulesetId)
        }
        rulesetRepository.insert(ruleset, itemsWithId)
        return ruleset.toResponse()
    }

    private fun digestRules(items: List<RulesetItem>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val content = items
            .sortedBy { it.ruleId }
            .joinToString("|") { "${it.ruleId}:${it.ruleHash}:${it.severity}:${it.enabled}" }
        val bytes = digest.digest(content.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun Rule.toResponse() = RuleResponse(
        ruleId = ruleId,
        tenantId = tenantId,
        scope = scope,
        key = key,
        name = name,
        engine = engine,
        langs = langs,
        severityDefault = severityDefault,
        tags = tags,
        enabled = enabled,
        hash = hash,
        signature = signature,
        createdAt = createdAt.toString()
    )

    private fun RuleGroup.toResponse() = RuleGroupResponse(
        groupId = groupId,
        key = key,
        name = name,
        description = description,
        createdAt = createdAt.toString()
    )

    private fun Ruleset.toResponse() = RulesetResponse(
        rulesetId = rulesetId,
        tenantId = tenantId,
        source = source,
        profile = profile,
        version = version,
        langs = langs,
        hash = hash,
        signature = signature,
        uri = uri
    )

    fun deleteRuleGroup(tenantId: UUID, groupId: UUID) {
        val group = ruleGroupRepository.findGroup(groupId, tenantId)
            ?: throw SecruxException(ErrorCode.RULE_GROUP_NOT_FOUND, "Rule group not found")
        ruleGroupRepository.softDeleteGroup(group.groupId, tenantId, OffsetDateTime.now(clock))
    }

    fun deleteRuleset(tenantId: UUID, rulesetId: UUID) {
        val ruleset = rulesetRepository.findById(rulesetId, tenantId)
            ?: throw SecruxException(ErrorCode.RULESET_NOT_FOUND, "Ruleset not found")
        rulesetRepository.softDelete(ruleset.rulesetId, tenantId, OffsetDateTime.now(clock))
    }
}
