package com.secrux.dto

import java.util.UUID

data class AiAgentUploadRequest(
    val name: String,
    val kind: String,
    val entrypoint: String?,
    val stageTypes: List<String> = emptyList(),
    val mcpProfileId: UUID?,
    val enabled: Boolean,
    val params: Map<String, Any?> = emptyMap()
)
