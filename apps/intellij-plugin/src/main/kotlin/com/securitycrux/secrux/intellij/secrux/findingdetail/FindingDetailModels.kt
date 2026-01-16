package com.securitycrux.secrux.intellij.secrux.findingdetail

data class FindingDetailModel(
    val findingId: String,
    val ruleId: String?,
    val sourceEngine: String?,
    val severity: String?,
    val status: String?,
    val taskName: String?,
    val taskId: String?,
    val projectName: String?,
    val projectId: String?,
    val locationPath: String?,
    val locationLine: Int?,
    val introducedBy: String?,
    val createdAt: String?,
    val review: FindingReviewModel?,
    val snippet: CodeSnippetModel?,
    val callChains: List<CallChainModel>,
    val enrichment: EnrichmentModel?,
)

data class FindingReviewModel(
    val reviewType: String?,
    val reviewer: String?,
    val verdict: String?,
    val confidence: String?,
    val summaryZh: String?,
    val fixHintZh: String?,
)

data class CodeSnippetModel(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val highlightedLine: Int?,
    val code: String,
)

data class CallChainModel(
    val chainId: String,
    val steps: List<CallChainStepModel>,
)

data class CallChainStepModel(
    val nodeId: String,
    val role: String?,
    val label: String,
    val file: String?,
    val line: Int?,
    val startColumn: Int?,
    val endColumn: Int?,
    val snippet: String?,
)

data class EnrichmentModel(
    val engine: String,
    val generatedAt: String?,
    val version: Int?,
    val blocks: List<EnrichmentBlockModel>,
    val primary: EnrichmentPrimaryModel?,
    val nodeMethods: List<EnrichedNodeMethodModel>,
    val externalSymbols: List<String>,
    val fieldDefinitions: List<String>,
)

data class EnrichmentBlockModel(
    val blockId: String,
    val kind: String,
    val reason: EnrichmentReasonModel?,
    val filePath: String?,
    val language: String?,
    val startLine: Int?,
    val endLine: Int?,
    val highlightLines: List<Int>,
    val related: EnrichmentRelatedModel?,
    val method: EnrichedMethodModel?,
    val conditions: List<EnrichedLineText>,
    val invocations: List<EnrichedLineText>,
)

data class EnrichmentReasonModel(
    val code: String?,
    val titleZh: String?,
    val titleEn: String?,
    val detailsZh: String?,
    val detailsEn: String?,
)

data class EnrichmentRelatedModel(
    val findingLine: Int?,
    val nodeId: String?,
    val label: String?,
    val role: String?,
    val chainIndex: Int?,
    val stepIndex: Int?,
)

data class EnrichmentPrimaryModel(
    val path: String?,
    val line: Int?,
    val method: EnrichedMethodModel?,
    val conditions: List<EnrichedLineText>,
    val invocations: List<EnrichedLineText>,
)

data class EnrichedNodeMethodModel(
    val nodeId: String,
    val label: String,
    val path: String?,
    val line: Int?,
    val chainIndex: Int?,
    val stepIndex: Int?,
    val method: EnrichedMethodModel?,
    val conditions: List<EnrichedLineText>,
    val invocations: List<EnrichedLineText>,
)

data class EnrichedMethodModel(
    val signature: String?,
    val name: String?,
    val startLine: Int?,
    val endLine: Int?,
    val language: String?,
    val text: String?,
    val truncated: Boolean,
)

data class EnrichedLineText(
    val line: Int?,
    val text: String,
)
