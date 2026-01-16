package com.secrux.dto

import java.util.UUID

data class TaskLogChunkResponse(
    val sequence: Long,
    val stream: String,
    val content: String,
    val createdAt: String,
    val isLast: Boolean,
    val stageId: UUID? = null,
    val stageType: String? = null,
    val level: String = "INFO"
)

