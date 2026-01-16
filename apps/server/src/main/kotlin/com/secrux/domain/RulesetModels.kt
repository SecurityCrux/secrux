package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class Ruleset(
    val rulesetId: UUID,
    val tenantId: UUID,
    val source: String,
    val version: String,
    val profile: String,
    val langs: List<String>,
    val hash: String,
    val signature: String?,
    val uri: String?,
    val deletedAt: OffsetDateTime?
)

data class RulesetItem(
    val id: UUID,
    val rulesetId: UUID,
    val ruleId: UUID,
    val engine: String,
    val severity: Severity,
    val enabled: Boolean,
    val ruleHash: String
)

