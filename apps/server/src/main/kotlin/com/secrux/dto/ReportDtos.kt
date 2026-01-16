package com.secrux.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

enum class ReportFormat {
    HTML,
    PDF,
    DOCX
}

enum class ReportDetailLevel {
    SUMMARY,
    FULL
}

@Schema(name = "ReportExportRequest")
data class ReportExportRequest(
    val findingIds: List<UUID> = emptyList(),
    @field:NotNull
    val format: ReportFormat = ReportFormat.HTML,
    @field:NotNull
    val detailLevel: ReportDetailLevel = ReportDetailLevel.SUMMARY,
    val includeCode: Boolean = true,
    val includeDataFlow: Boolean = true,
    val locale: String? = null,
    val title: String? = null
)
