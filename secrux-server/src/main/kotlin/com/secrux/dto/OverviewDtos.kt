package com.secrux.dto

import com.secrux.domain.ExecutorStatus
import com.secrux.domain.FindingStatus
import com.secrux.domain.Severity
import com.secrux.domain.TaskStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(name = "OverviewExecutorSummary")
data class OverviewExecutorSummary(
    val total: Int,
    val byStatus: Map<ExecutorStatus, Int>
)

@Schema(name = "OverviewTaskSummary")
data class OverviewTaskSummary(
    val running: Int,
    val failed24h: Int,
    val totalWindow: Int,
    val successRateWindow: Double,
    val byStatus: Map<TaskStatus, Int>
)

@Schema(name = "OverviewFindingTrendPoint")
data class OverviewFindingTrendPoint(
    val date: String,
    val newCount: Int,
    val closedCount: Int
)

@Schema(name = "OverviewFindingSummary")
data class OverviewFindingSummary(
    val open: Int,
    val confirmed: Int,
    val bySeverityUnresolved: Map<Severity, Int>,
    val trendWindow: List<OverviewFindingTrendPoint>
)

@Schema(name = "OverviewAiSummary")
data class OverviewAiSummary(
    val queued: Int,
    val completedWindow: Int,
    val autoAppliedWindow: Int
)

@Schema(name = "OverviewSummaryResponse")
data class OverviewSummaryResponse(
    val projects: Int,
    val repositories: Int,
    val executors: OverviewExecutorSummary,
    val tasks: OverviewTaskSummary,
    val findings: OverviewFindingSummary,
    val ai: OverviewAiSummary
)

@Schema(name = "OverviewTaskItem")
data class OverviewTaskItem(
    val taskId: UUID,
    val projectId: UUID,
    val repoId: UUID?,
    val status: TaskStatus,
    val type: String,
    val name: String?,
    val correlationId: String,
    val createdAt: String,
    val updatedAt: String?
)

@Schema(name = "OverviewFindingItem")
data class OverviewFindingItem(
    val findingId: UUID,
    val taskId: UUID,
    val projectId: UUID,
    val ruleId: String?,
    val severity: Severity,
    val status: FindingStatus,
    val introducedBy: String?,
    val createdAt: String
)
