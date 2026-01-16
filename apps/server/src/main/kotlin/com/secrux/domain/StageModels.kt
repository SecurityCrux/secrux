package com.secrux.domain

import java.time.OffsetDateTime
import java.util.UUID

enum class StageType {
    SOURCE_PREPARE,
    RULES_PREPARE,
    SCAN_EXEC,
    RESULT_PROCESS,
    RESULT_REVIEW,
    TICKET_CREATE,
    FEEDBACK_SYNC
}

enum class StageStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

data class Stage(
    val stageId: UUID,
    val tenantId: UUID,
    val taskId: UUID,
    val type: StageType,
    val spec: StageSpec,
    val status: StageStatus,
    val metrics: StageMetrics,
    val signals: StageSignals,
    val artifacts: List<String>,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?
)

data class StageSpec(
    val version: String,
    val inputs: Map<String, Any?> = emptyMap(),
    val params: Map<String, Any?> = emptyMap(),
    val resources: ResourceLimits? = null,
    val env: Map<String, String> = emptyMap(),
    val shards: List<Map<String, Any?>> = emptyList(),
    val trace: Map<String, Any?> = emptyMap()
)

data class ResourceLimits(
    val cpu: String? = null,
    val memory: String? = null,
    val timeoutSec: Int? = null
)

data class StageMetrics(
    val durationMs: Long? = null,
    val cpuUsage: Double? = null,
    val memoryUsageMb: Double? = null,
    val retryCount: Int = 0,
    val artifactSizeBytes: Long? = null
)

data class StageSignals(
    val needsAiReview: Boolean = false,
    val autoFixPossible: Boolean = false,
    val riskDelta: Double? = null,
    val hasSink: Boolean? = null
)

data class ScanShard(
    val shardId: UUID,
    val taskId: UUID,
    val tenantId: UUID,
    val path: String,
    val engine: String,
    val status: StageStatus,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?
)

