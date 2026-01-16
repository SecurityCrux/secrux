package com.secrux.service

import com.secrux.domain.Finding
import com.secrux.dto.FindingLocationSummary
import com.secrux.dto.FindingReviewSummary
import com.secrux.dto.FindingSummary
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class FindingSummaryMapper(
    private val evidenceService: FindingEvidenceService
) {

    fun toSummary(
        finding: Finding,
        review: FindingReviewSummary? = null
    ): FindingSummary =
        FindingSummary(
            findingId = finding.findingId,
            ruleId = finding.ruleId,
            sourceEngine = finding.sourceEngine,
            severity = finding.severity,
            status = finding.status,
            location = toLocationSummary(finding),
            introducedBy = finding.introducedBy,
            hasDataFlow = evidenceService.hasDataFlow(finding.evidence),
            review = review
        )

    fun toSummaries(
        findings: List<Finding>,
        reviews: Map<UUID, FindingReviewSummary?> = emptyMap()
    ): List<FindingSummary> = findings.map { toSummary(it, reviews[it.findingId]) }

    private fun toLocationSummary(finding: Finding): FindingLocationSummary {
        val pathRaw = finding.location["path"]?.toString()
        val path = evidenceService.normalizeWorkspaceNodeFile(finding.taskId, pathRaw)
        val line = (finding.location["line"] as? Number)?.toInt()
        val startLine = (finding.location["startLine"] as? Number)?.toInt()
        val startColumn = (finding.location["startColumn"] as? Number)?.toInt()
        val endColumn = (finding.location["endColumn"] as? Number)?.toInt()
        return FindingLocationSummary(
            path = path,
            line = line,
            startLine = startLine,
            startColumn = startColumn,
            endColumn = endColumn
        )
    }
}
