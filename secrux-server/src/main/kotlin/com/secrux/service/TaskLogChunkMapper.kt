package com.secrux.service

import com.secrux.domain.TaskLogChunk
import com.secrux.dto.TaskLogChunkResponse

internal fun TaskLogChunk.toResponse(): TaskLogChunkResponse =
    TaskLogChunkResponse(
        sequence = sequence,
        stream = stream.name.lowercase(),
        content = content,
        createdAt = createdAt.toString(),
        isLast = isLast,
        stageId = stageId,
        stageType = stageType?.name,
        level = level.name
    )

