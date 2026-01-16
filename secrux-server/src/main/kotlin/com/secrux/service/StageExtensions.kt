package com.secrux.service

import com.secrux.domain.Stage
import com.secrux.dto.StageSummary

fun Stage.toSummary(): StageSummary = StageSummary(
    stageId = stageId,
    taskId = taskId,
    type = type.name,
    status = status.name,
    artifacts = artifacts,
    startedAt = startedAt?.toString(),
    endedAt = endedAt?.toString()
)

