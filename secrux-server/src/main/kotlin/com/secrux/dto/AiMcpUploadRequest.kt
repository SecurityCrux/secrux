package com.secrux.dto

data class AiMcpUploadRequest(
    val name: String,
    val entrypoint: String?,
    val enabled: Boolean,
    val params: Map<String, Any?>
)
