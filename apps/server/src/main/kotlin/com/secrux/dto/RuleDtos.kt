package com.secrux.dto

import com.secrux.domain.Severity
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "RuleUpsertRequest")
data class RuleUpsertRequest(
    @field:NotBlank val scope: String,
    @field:NotBlank val key: String,
    @field:NotBlank val name: String,
    @field:NotBlank val engine: String,
    val langs: List<String> = emptyList(),
    val severityDefault: Severity,
    val tags: List<String> = emptyList(),
    val pattern: Map<String, Any?>,
    val docs: Map<String, Any?>? = null,
    val enabled: Boolean = true,
    @field:NotBlank val hash: String,
    val signature: String? = null
)

@Schema(name = "RuleResponse")
data class RuleResponse(
    val ruleId: UUID,
    val tenantId: UUID,
    val scope: String,
    val key: String,
    val name: String,
    val engine: String,
    val langs: List<String>,
    val severityDefault: Severity,
    val tags: List<String>,
    val enabled: Boolean,
    val hash: String,
    val signature: String?,
    val createdAt: String
)

@Schema(name = "RuleGroupRequest")
data class RuleGroupRequest(
    @field:NotBlank val key: String,
    @field:NotBlank val name: String,
    val description: String? = null
)

@Schema(name = "RuleGroupResponse")
data class RuleGroupResponse(
    val groupId: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val createdAt: String
)

@Schema(name = "RuleGroupMemberRequest")
data class RuleGroupMemberRequest(
    @field:NotNull val ruleId: UUID,
    val overrideEnabled: Boolean? = null,
    val overrideSeverity: Severity? = null
)

@Schema(name = "RulesetPublishRequest")
data class RulesetPublishRequest(
    @field:NotNull val groupId: UUID,
    @field:NotBlank val profile: String,
    val version: String? = null,
    val signature: String? = null
)

@Schema(name = "RulesetResponse")
data class RulesetResponse(
    val rulesetId: UUID,
    val tenantId: UUID,
    val source: String,
    val profile: String,
    val version: String,
    val langs: List<String>,
    val hash: String,
    val signature: String?,
    val uri: String?
)

