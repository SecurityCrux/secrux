package com.secrux.service

import com.secrux.common.ErrorCode
import com.secrux.common.SecruxException
import com.secrux.domain.FindingStatus
import com.secrux.domain.ScaIssue
import com.secrux.domain.Severity
import com.secrux.domain.TaskType
import com.secrux.dto.CvssSummary
import com.secrux.dto.PageResponse
import com.secrux.dto.ScaIssueDetailResponse
import com.secrux.dto.ScaIssueSummary
import com.secrux.repo.ProjectRepository
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.TaskRepository
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ScaIssueService(
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val scaIssueRepository: ScaIssueRepository,
    private val scaIssueReviewService: ScaIssueReviewService,
) {

    fun listIssuesByTask(
        tenantId: UUID,
        taskId: UUID,
        status: FindingStatus?,
        severity: Severity?,
        search: String?,
        limit: Int,
        offset: Int
    ): PageResponse<ScaIssueSummary> {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        if (task.type != TaskType.SCA_CHECK) {
            throw SecruxException(ErrorCode.VALIDATION_ERROR, "Task ${task.taskId} is not an SCA task")
        }
        val (items, total) =
            scaIssueRepository.listByTaskPaged(
                tenantId = tenantId,
                taskId = taskId,
                status = status,
                severity = severity,
                search = search,
                limit = limit,
                offset = offset
            )
        val reviews = scaIssueReviewService.latestReviews(tenantId, items.map { it.issueId })
        return PageResponse(
            items = items.map { issue -> issue.toSummary(reviews[issue.issueId]) },
            total = total,
            limit = limit,
            offset = offset
        )
    }

    fun getIssueDetail(
        tenantId: UUID,
        taskId: UUID,
        issueId: UUID
    ): ScaIssueDetailResponse {
        val task = taskRepository.findById(taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        val issue = scaIssueRepository.findById(tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        if (issue.taskId != taskId) {
            throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found for task")
        }
        val project = projectRepository.findById(issue.projectId, tenantId)
        val review = scaIssueReviewService.latestReview(tenantId, issueId)
        return issue.toDetail(task.name, project?.name, review)
    }

    fun getIssueDetail(
        tenantId: UUID,
        issueId: UUID
    ): ScaIssueDetailResponse {
        val issue = scaIssueRepository.findById(tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        val task = taskRepository.findById(issue.taskId, tenantId)
            ?: throw SecruxException(ErrorCode.TASK_NOT_FOUND, "Task not found")
        val project = projectRepository.findById(issue.projectId, tenantId)
        val review = scaIssueReviewService.latestReview(tenantId, issueId)
        return issue.toDetail(task.name, project?.name, review)
    }

    fun updateStatus(
        principal: SecruxPrincipal,
        taskId: UUID,
        issueId: UUID,
        status: FindingStatus
    ): ScaIssueSummary {
        getIssueDetail(principal.tenantId, taskId, issueId)
        val issue = scaIssueRepository.findById(principal.tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        if (issue.status != status) {
            scaIssueRepository.updateStatus(principal.tenantId, issueId, status)
        }
        val updated = scaIssueRepository.findById(principal.tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        val review =
            if (issue.status != status) {
                scaIssueReviewService.recordHumanReview(principal, issue, status)
            } else {
                scaIssueReviewService.latestReview(principal.tenantId, issueId)
            }
        return updated.toSummary(review)
    }

    fun updateStatus(
        principal: SecruxPrincipal,
        issueId: UUID,
        status: FindingStatus
    ): ScaIssueSummary {
        val issue = scaIssueRepository.findById(principal.tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        if (issue.status != status) {
            scaIssueRepository.updateStatus(principal.tenantId, issueId, status)
        }
        val updated = scaIssueRepository.findById(principal.tenantId, issueId)
            ?: throw SecruxException(ErrorCode.SCA_ISSUE_NOT_FOUND, "SCA issue not found")
        val review =
            if (issue.status != status) {
                scaIssueReviewService.recordHumanReview(principal, issue, status)
            } else {
                scaIssueReviewService.latestReview(principal.tenantId, issueId)
            }
        return updated.toSummary(review)
    }

    private fun ScaIssue.toSummary(review: com.secrux.dto.FindingReviewSummary?): ScaIssueSummary =
        ScaIssueSummary(
            issueId = issueId,
            vulnId = vulnId,
            sourceEngine = sourceEngine,
            severity = severity,
            status = status,
            packageName = packageName,
            installedVersion = installedVersion,
            fixedVersion = fixedVersion,
            primaryUrl = primaryUrl,
            componentPurl = componentPurl,
            componentName = componentName,
            componentVersion = componentVersion,
            introducedBy = introducedBy,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt?.toString(),
            review = review
        )

    private fun ScaIssue.toDetail(
        taskName: String?,
        projectName: String?,
        review: com.secrux.dto.FindingReviewSummary?
    ): ScaIssueDetailResponse =
        run {
            val evidenceSummary = summarizeEvidence(evidence)
            ScaIssueDetailResponse(
                issueId = issueId,
                taskId = taskId,
                taskName = taskName,
                projectId = projectId,
                projectName = projectName,
                vulnId = vulnId,
                sourceEngine = sourceEngine,
                title = evidenceSummary.title,
                description = evidenceSummary.description,
                references = evidenceSummary.references,
                cvss = evidenceSummary.cvss,
                severity = severity,
                status = status,
                packageName = packageName,
                installedVersion = installedVersion,
                fixedVersion = fixedVersion,
                primaryUrl = primaryUrl,
                componentPurl = componentPurl,
                componentName = componentName,
                componentVersion = componentVersion,
                introducedBy = introducedBy,
                createdAt = createdAt.toString(),
                updatedAt = updatedAt?.toString(),
                review = review
            )
        }

    private data class ScaIssueEvidenceSummary(
        val title: String?,
        val description: String?,
        val references: List<String>,
        val cvss: CvssSummary?
    )

    private fun summarizeEvidence(evidence: Map<String, Any?>?): ScaIssueEvidenceSummary {
        val title =
            (evidence?.get("title") as? String)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val description =
            (evidence?.get("description") as? String)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        val references =
            (evidence?.get("references") as? Iterable<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?: emptyList()

        val cvss = parseCvssSummary(evidence?.get("cvss"))

        return ScaIssueEvidenceSummary(
            title = title,
            description = description,
            references = references,
            cvss = cvss
        )
    }

    private fun parseCvssSummary(raw: Any?): CvssSummary? {
        val map = raw as? Map<*, *> ?: return null
        var bestScore: Double? = null
        var bestVector: String? = null
        var bestSource: String? = null

        for ((sourceKey, value) in map.entries) {
            val source = sourceKey?.toString()?.trim().takeUnless { it.isNullOrBlank() } ?: continue
            val entry = value as? Map<*, *> ?: continue
            val scoreAny = entry["v3Score"]
            val score =
                when (scoreAny) {
                    is Number -> scoreAny.toDouble()
                    is String -> scoreAny.toDoubleOrNull()
                    else -> null
                } ?: continue
            if (bestScore == null || score > bestScore) {
                bestScore = score
                bestVector = entry["v3Vector"] as? String
                bestSource = source
            }
        }

        val finalScore = bestScore ?: return null
        return CvssSummary(score = finalScore, vector = bestVector, source = bestSource)
    }
}
