package com.secrux.dto

import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "FindingIngestRequest")
data class FindingIngestRequest(
    @field:NotBlank val sourceEngine: String,
    val ruleId: String? = null,
    @field:NotBlank val fingerprint: String,
    @field:NotNull val severity: Severity,
    val location: Map<String, Any?>,
    val evidence: Map<String, Any?>? = null,
    val introducedBy: String? = null,
    val fixVersion: String? = null,
    val exploitMaturity: String? = null,
    val packageName: String? = null,
    val status: FindingStatus = FindingStatus.OPEN
)

@Schema(name = "FindingBatchRequest")
data class FindingBatchRequest(
    @field:NotEmpty val findings: List<FindingIngestRequest>,
    val format: String? = "SARIF",
    val artifactLocations: List<String>? = null
)

data class FindingLocationSummary(
    val path: String? = null,
    val line: Int? = null,
    val startLine: Int? = null,
    val startColumn: Int? = null,
    val endColumn: Int? = null
)

@Schema(name = "FindingSummary")
data class FindingSummary(
    val findingId: UUID,
    val ruleId: String?,
    val sourceEngine: String,
    val severity: Severity,
    val status: FindingStatus,
    val location: FindingLocationSummary,
    val introducedBy: String?,
    val hasDataFlow: Boolean = false,
    val review: FindingReviewSummary? = null
)

@Schema(name = "FindingReviewOpinionText")
data class FindingReviewOpinionText(
    val summary: String? = null,
    val fixHint: String? = null,
    val rationale: String? = null
)

@Schema(name = "FindingReviewOpinionI18n")
data class FindingReviewOpinionI18n(
    val zh: FindingReviewOpinionText? = null,
    val en: FindingReviewOpinionText? = null
)

@Schema(name = "FindingReviewSummary")
data class FindingReviewSummary(
    val reviewType: String,
    val reviewer: String,
    val verdict: String,
    val confidence: Double? = null,
    val statusBefore: FindingStatus? = null,
    val statusAfter: FindingStatus? = null,
    val opinionI18n: FindingReviewOpinionI18n? = null,
    val createdAt: String,
    val appliedAt: String? = null
)

data class CodeLineDto(
    val lineNumber: Int,
    val content: String,
    val highlight: Boolean = false
)

data class CodeSnippetDto(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val lines: List<CodeLineDto>
)

data class DataFlowNodeDto(
    val id: String,
    val label: String,
    val role: String? = null,
    val value: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val startColumn: Int? = null,
    val endColumn: Int? = null
)

data class DataFlowEdgeDto(
    val source: String,
    val target: String,
    val label: String? = null
)

data class FindingDetailResponse(
    val findingId: UUID,
    val taskId: UUID,
    val taskName: String?,
    val projectId: UUID,
    val projectName: String?,
    val repoId: UUID?,
    val repoRemoteUrl: String?,
    val ruleId: String?,
    val sourceEngine: String,
    val severity: Severity,
    val status: FindingStatus,
    val location: FindingLocationSummary,
    val introducedBy: String?,
    val createdAt: String,
    val updatedAt: String?,
    val codeSnippet: CodeSnippetDto?,
    val dataFlowNodes: List<DataFlowNodeDto>,
    val dataFlowEdges: List<DataFlowEdgeDto>,
    val callChains: List<CallChainDto> = emptyList(),
    val enrichment: Map<String, Any?>? = null,
    val review: FindingReviewSummary? = null
)

@Schema(name = "FindingStatusUpdateRequest")
data class FindingStatusUpdateRequest(
    @field:NotNull val status: FindingStatus,
    val reason: String? = null,
    val fixVersion: String? = null
)

@Schema(name = "FindingBatchStatusUpdateRequest")
data class FindingBatchStatusUpdateRequest(
    @field:NotEmpty val findingIds: List<UUID>,
    @field:NotNull val status: FindingStatus,
    val reason: String? = null,
    val fixVersion: String? = null
)

data class FindingBatchStatusUpdateFailure(
    val findingId: UUID,
    val error: String
)

@Schema(name = "FindingBatchStatusUpdateResponse")
data class FindingBatchStatusUpdateResponse(
    val updated: List<FindingSummary>,
    val failed: List<FindingBatchStatusUpdateFailure> = emptyList()
)
