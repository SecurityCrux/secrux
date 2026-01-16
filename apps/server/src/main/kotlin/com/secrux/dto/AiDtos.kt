package com.secrux.dto

import com.secrux.ai.AiJobStatus
import com.secrux.ai.AiJobType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

@Schema(name = "AiReviewTriggerRequest")
data class AiReviewTriggerRequest(
    @field:Size(max = 5000) val context: String? = null,
    val mode: String? = null,
    val agent: String? = null
)

@Schema(name = "AiJobTicketResponse")
data class AiJobTicketResponse(
    val jobId: String,
    val status: AiJobStatus,
    val jobType: AiJobType,
    val createdAt: String,
    val updatedAt: String,
    val result: Map<String, Any?>? = null,
    val error: String? = null
)

@Schema(name = "AiClientConfigRequest")
data class AiClientConfigRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val provider: String,
    @field:NotBlank val baseUrl: String,
    val apiKey: String? = null,
    @field:NotBlank val model: String,
    val isDefault: Boolean = false,
    val enabled: Boolean = true
)

@Schema(name = "AiClientConfigResponse")
data class AiClientConfigResponse(
    val configId: UUID,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val isDefault: Boolean,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "AiProviderTemplateResponse")
data class AiProviderTemplateResponse(
    val provider: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val regions: List<String>,
    val models: List<String>,
    val docsUrl: String?,
    val description: String?
)

@Schema(name = "AiMcpConfigRequest")
data class AiMcpConfigRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val type: String,
    val endpoint: String? = null,
    val entrypoint: String? = null,
    val params: Map<String, Any?>? = emptyMap(),
    val enabled: Boolean = true
)

@Schema(name = "AiMcpConfigResponse")
data class AiMcpConfigResponse(
    val profileId: UUID,
    val name: String,
    val type: String,
    val endpoint: String?,
    val entrypoint: String?,
    val params: Map<String, Any?>,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "AiAgentConfigRequest")
data class AiAgentConfigRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val kind: String,
    val entrypoint: String? = null,
    val params: Map<String, Any?>? = emptyMap(),
    val stageTypes: List<String> = emptyList(),
    val mcpProfileId: UUID? = null,
    val enabled: Boolean = true
)

@Schema(name = "AiAgentConfigResponse")
data class AiAgentConfigResponse(
    val agentId: UUID,
    val name: String,
    val kind: String,
    val entrypoint: String?,
    val params: Map<String, Any?>,
    val stageTypes: List<String>,
    val mcpProfileId: UUID?,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "AiKnowledgeEntryRequest")
data class AiKnowledgeEntryRequest(
    @field:NotBlank val title: String,
    @field:NotBlank val body: String,
    val tags: List<String> = emptyList(),
    val sourceUri: String? = null,
    val embedding: List<Double>? = null
)

@Schema(name = "AiKnowledgeEntryResponse")
data class AiKnowledgeEntryResponse(
    val entryId: UUID,
    val title: String,
    val body: String,
    val tags: List<String>,
    val sourceUri: String?,
    val embedding: List<Double>?,
    val createdAt: String,
    val updatedAt: String
)

@Schema(name = "AiKnowledgeSearchRequest")
data class AiKnowledgeSearchRequest(
    @field:NotBlank val query: String,
    val limit: Int = 5,
    val tags: List<String> = emptyList()
)

@Schema(name = "AiKnowledgeSearchHit")
data class AiKnowledgeSearchHit(
    val entryId: UUID,
    val title: String,
    val snippet: String,
    val score: Double,
    val sourceUri: String?,
    val tags: List<String>
)

