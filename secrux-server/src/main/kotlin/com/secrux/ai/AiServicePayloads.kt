package com.secrux.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.secrux.dto.AiAgentConfigResponse
import com.secrux.dto.AiKnowledgeEntryResponse
import com.secrux.dto.AiKnowledgeSearchHit
import com.secrux.dto.AiMcpConfigResponse
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal data class AiJobTicketPayload(
    @JsonProperty("jobId") val jobId: String,
    @JsonProperty("status") val status: AiJobStatus,
    @JsonProperty("jobType") val jobType: AiJobType,
    @JsonProperty("tenantId") val tenantId: String,
    @JsonProperty("targetId") val targetId: String,
    @JsonProperty("createdAt") val createdAt: String,
    @JsonProperty("updatedAt") val updatedAt: String,
    @JsonProperty("result") val result: Map<String, Any?>?,
    @JsonProperty("error") val error: String?
) {
    fun toDomain() =
        AiJobTicket(
            jobId = jobId,
            status = status,
            jobType = jobType,
            tenantId = tenantId,
            targetId = targetId,
            createdAt = parseOffsetDateTime(createdAt),
            updatedAt = parseOffsetDateTime(updatedAt),
            result = result,
            error = error
        )
}

internal data class McpListPayload(
    val data: List<AiMcpConfigResponse>
)

internal data class AgentListPayload(
    val data: List<AiAgentConfigResponse>
)

internal data class KnowledgeListPayload(
    val data: List<AiKnowledgeEntryResponse>
)

internal data class KnowledgeSearchPayload(
    val data: List<AiKnowledgeSearchHit>
)

internal fun parseOffsetDateTime(value: String): OffsetDateTime =
    runCatching { OffsetDateTime.parse(value) }
        .getOrElse { LocalDateTime.parse(value).atOffset(ZoneOffset.UTC) }

