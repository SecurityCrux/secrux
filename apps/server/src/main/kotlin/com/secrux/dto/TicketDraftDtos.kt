package com.secrux.dto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.domain.TicketDraftItemType
import com.secrux.domain.TicketIssueType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import java.util.UUID

@Schema(name = "TicketProviderPolicyDefaults")
data class TicketProviderPolicyDefaultsPayload(
    val project: String,
    val assigneeStrategy: String,
    val labels: List<String> = emptyList()
)

@Schema(name = "TicketProviderTemplate")
data class TicketProviderTemplateResponse(
    val provider: String,
    val name: String,
    val enabled: Boolean,
    val defaultPolicy: TicketProviderPolicyDefaultsPayload
)

@Schema(name = "TicketDraftItem")
data class TicketDraftItem(
    val type: TicketDraftItemType,
    val id: UUID,
    val title: String?,
    val severity: Severity,
    val status: FindingStatus,
    val location: Map<String, Any?>
)

@Schema(name = "TicketDraftDetail")
data class TicketDraftDetailResponse(
    val draftId: UUID,
    val projectId: UUID?,
    val provider: String?,
    val items: List<TicketDraftItem>,
    val itemCount: Int,
    val titleI18n: Map<String, Any?>?,
    val descriptionI18n: Map<String, Any?>?,
    val lastAiJobId: String?,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "TicketDraftItemRef")
data class TicketDraftItemRefPayload(
    val type: TicketDraftItemType,
    val id: UUID
)

@Schema(name = "TicketDraftItemsRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketDraftItemsRequest @JsonCreator constructor(
    @param:JsonProperty("items")
    @field:NotEmpty
    val items: List<TicketDraftItemRefPayload>
)

@Schema(name = "TicketDraftUpdateRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketDraftUpdateRequest @JsonCreator constructor(
    @param:JsonProperty("provider")
    val provider: String? = null,
    @param:JsonProperty("titleI18n")
    val titleI18n: Map<String, Any?>? = null,
    @param:JsonProperty("descriptionI18n")
    val descriptionI18n: Map<String, Any?>? = null
)

@Schema(name = "TicketCreateFromDraftRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketCreateFromDraftRequest @JsonCreator constructor(
    @param:JsonProperty("provider")
    @field:NotBlank
    val provider: String,
    @param:JsonProperty("issueType")
    val issueType: TicketIssueType = TicketIssueType.BUG,
    @param:JsonProperty("clearDraft")
    val clearDraft: Boolean = true
)

@Schema(name = "TicketDraftAiRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketDraftAiRequest @JsonCreator constructor(
    @param:JsonProperty("provider")
    val provider: String? = null
)

@Schema(name = "TicketDraftAiApplyRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class TicketDraftAiApplyRequest @JsonCreator constructor(
    @param:JsonProperty("jobId")
    @field:NotBlank
    val jobId: String
)
