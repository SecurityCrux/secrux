package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class AiClientConfig(
    val configId: UUID,
    val tenantId: UUID,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val isDefault: Boolean,
    val enabled: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class AiMcpConfig(
    val profileId: UUID,
    val tenantId: UUID,
    val name: String,
    val type: String,
    val endpoint: String?,
    val entrypoint: String?,
    val params: Map<String, Any?>,
    val enabled: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

data class AiAgentConfig(
    val agentId: UUID,
    val tenantId: UUID,
    val name: String,
    val kind: String,
    val entrypoint: String?,
    val params: Map<String, Any?>,
    val stageTypes: List<String>,
    val mcpProfileId: UUID?,
    val enabled: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime?
)

