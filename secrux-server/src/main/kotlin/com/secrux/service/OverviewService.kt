package com.secrux.service

import com.secrux.dto.OverviewFindingItem
import com.secrux.dto.OverviewSummaryResponse
import com.secrux.dto.OverviewTaskItem
import com.secrux.dto.PageResponse
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class OverviewService(
    private val catalogQueryService: OverviewCatalogQueryService,
    private val executorQueryService: OverviewExecutorQueryService,
    private val taskQueryService: OverviewTaskQueryService,
    private val findingQueryService: OverviewFindingQueryService,
    private val aiQueryService: OverviewAiQueryService,
    private val clock: Clock
) {

    fun getSummary(tenantId: UUID, window: String): OverviewSummaryResponse {
        val now = OffsetDateTime.now(clock)
        val windowStart = now.minus(parseWindow(window))
        val dayStart = now.minus(24, ChronoUnit.HOURS)

        val projects = catalogQueryService.countProjects(tenantId)
        val repositories = catalogQueryService.countRepositories(tenantId)
        val executors = executorQueryService.getExecutorSummary(tenantId)
        val tasks = taskQueryService.getTaskSummary(tenantId, windowStart, dayStart)
        val findings = findingQueryService.getFindingSummary(tenantId, windowStart)
        val ai = aiQueryService.getAiSummary(tenantId, windowStart)

        return OverviewSummaryResponse(
            projects = projects,
            repositories = repositories,
            executors = executors,
            tasks = tasks,
            findings = findings,
            ai = ai
        )
    }

    fun listRecentTasks(tenantId: UUID, limit: Int, offset: Int): PageResponse<OverviewTaskItem> {
        return taskQueryService.listRecentTasks(tenantId, limit, offset)
    }

    fun listStuckTasks(tenantId: UUID, thresholdSeconds: Long, limit: Int, offset: Int): PageResponse<OverviewTaskItem> {
        return taskQueryService.listStuckTasks(tenantId, thresholdSeconds, limit, offset)
    }

    fun listTopFindings(tenantId: UUID, limit: Int, offset: Int): PageResponse<OverviewFindingItem> {
        return findingQueryService.listTopFindings(tenantId, limit, offset)
    }

    private fun parseWindow(window: String): java.time.Duration {
        val trimmed = window.trim().lowercase()
        if (trimmed.isBlank()) return java.time.Duration.ofDays(7)
        val numPart = trimmed.dropLast(1)
        val unit = trimmed.last()
        val amount = numPart.toLongOrNull() ?: 7L
        return when (unit) {
            'd' -> java.time.Duration.ofDays(amount)
            'h' -> java.time.Duration.ofHours(amount)
            else -> java.time.Duration.ofDays(7)
        }
    }
}
