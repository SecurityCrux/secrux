package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class Rule(
    val ruleId: UUID,
    val tenantId: UUID,
    val scope: String,
    val key: String,
    val name: String,
    val engine: String,
    val langs: List<String>,
    val severityDefault: Severity,
    val tags: List<String>,
    val pattern: Map<String, Any?>,
    val docs: Map<String, Any?>?,
    val enabled: Boolean,
    val hash: String,
    val signature: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?,
    val deprecatedAt: OffsetDateTime?
)

data class RuleGroup(
    val groupId: UUID,
    val tenantId: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?,
    val deletedAt: OffsetDateTime?
)

data class RuleGroupMember(
    val id: UUID,
    val tenantId: UUID,
    val groupId: UUID,
    val ruleId: UUID,
    val overrideEnabled: Boolean?,
    val overrideSeverity: Severity?,
    val createdAt: OffsetDateTime
)

