package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

data class TaskLogChunk(
    val chunkId: UUID,
    val taskId: UUID,
    val sequence: Long,
    val stream: LogStream,
    val content: String,
    val isLast: Boolean,
    val createdAt: OffsetDateTime,
    val stageId: UUID? = null,
    val stageType: StageType? = null,
    val level: LogLevel = LogLevel.INFO
)

enum class LogStream { STDOUT, STDERR }

enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }
