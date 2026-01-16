package com.secrux.dto

import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.util.UUID

@Schema(name = "ScaIssueSummary")
data class ScaIssueSummary(
    val issueId: UUID,
    val vulnId: String,
    val sourceEngine: String,
    val severity: Severity,
    val status: FindingStatus,
    val packageName: String?,
    val installedVersion: String?,
    val fixedVersion: String?,
    val primaryUrl: String?,
    val componentPurl: String?,
    val componentName: String?,
    val componentVersion: String?,
    val introducedBy: String?,
    val createdAt: String,
    val updatedAt: String?,
    val review: FindingReviewSummary? = null
)

@Schema(name = "ScaIssueDetailResponse")
data class ScaIssueDetailResponse(
    val issueId: UUID,
    val taskId: UUID,
    val taskName: String?,
    val projectId: UUID,
    val projectName: String?,
    val vulnId: String,
    val sourceEngine: String,
    val title: String?,
    val description: String?,
    val references: List<String> = emptyList(),
    val cvss: CvssSummary?,
    val severity: Severity,
    val status: FindingStatus,
    val packageName: String?,
    val installedVersion: String?,
    val fixedVersion: String?,
    val primaryUrl: String?,
    val componentPurl: String?,
    val componentName: String?,
    val componentVersion: String?,
    val introducedBy: String?,
    val createdAt: String,
    val updatedAt: String?,
    val review: FindingReviewSummary? = null
)

data class CvssSummary(
    val score: Double,
    val vector: String? = null,
    val source: String? = null
)

@Schema(name = "ScaIssueStatusUpdateRequest")
data class ScaIssueStatusUpdateRequest(
    @field:NotNull val status: FindingStatus
)
