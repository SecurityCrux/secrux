package com.secrux.dto

import com.secrux.domain.StageStatus
import com.secrux.domain.StageType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "StageSummary")
data class StageSummary(
    val stageId: UUID,
    val taskId: UUID,
    val type: String,
    val status: String,
    val artifacts: List<String>,
    val startedAt: String?,
    val endedAt: String?
)

@Schema(name = "ArtifactSummary")
data class ArtifactSummary(
    val artifactId: UUID,
    val stageId: UUID,
    val uri: String,
    val kind: String,
    val checksum: String?,
    val sizeBytes: Long?
)

@Schema(name = "StageUpsertRequest")
data class StageUpsertRequest(
    @field:NotNull val type: StageType,
    @field:NotBlank val version: String,
    @field:NotNull val status: StageStatus,
    val inputs: Map<String, Any?>? = null,
    val params: Map<String, Any?>? = null,
    val resources: ResourceLimitsPayload? = null,
    val env: Map<String, String>? = null,
    val shards: List<Map<String, Any?>>? = null,
    val trace: Map<String, Any?>? = null,
    val metrics: StageMetricsPayload? = null,
    val signals: StageSignalsPayload? = null,
    val artifacts: List<String>? = null,
    val startedAt: String? = null,
    val endedAt: String? = null
)

@Schema(name = "StageStatusUpdateRequest")
data class StageStatusUpdateRequest(
    val status: StageStatus? = null,
    val metrics: StageMetricsPayload? = null,
    val signals: StageSignalsPayload? = null,
    val artifacts: List<String>? = null,
    val startedAt: String? = null,
    val endedAt: String? = null
)

data class StageMetricsPayload(
    val durationMs: Long? = null,
    val cpuUsage: Double? = null,
    val memoryUsageMb: Double? = null,
    val retryCount: Int? = null,
    val artifactSizeBytes: Long? = null
)

data class StageSignalsPayload(
    val needsAiReview: Boolean? = null,
    val autoFixPossible: Boolean? = null,
    val riskDelta: Double? = null,
    val hasSink: Boolean? = null
)

data class ResourceLimitsPayload(
    val cpu: String? = null,
    val memory: String? = null,
    val timeoutSec: Int? = null
)

