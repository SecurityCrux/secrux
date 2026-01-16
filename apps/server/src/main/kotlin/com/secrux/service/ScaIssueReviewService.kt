package com.secrux.service

import com.secrux.ai.AiJobTicket
import com.secrux.ai.AiJobStatus
import com.secrux.ai.AiJobType
import com.secrux.domain.FindingStatus
import com.secrux.domain.ScaIssue
import com.secrux.dto.FindingReviewSummary
import com.secrux.repo.ScaIssueRepository
import com.secrux.repo.ScaIssueReviewRecord
import com.secrux.repo.ScaIssueReviewRepository
import com.secrux.security.SecruxPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ScaIssueReviewService(
    private val scaIssueRepository: ScaIssueRepository,
    private val scaIssueReviewRepository: ScaIssueReviewRepository,
    @Value("\${secrux.ai.review.sca.autoApply:false}") private val autoApply: Boolean,
    private val clock: Clock
) {

    fun latestReview(tenantId: UUID, issueId: UUID): FindingReviewSummary? =
        scaIssueReviewRepository.findLatestByIssueId(tenantId, issueId)?.toSummary()

    fun latestReviews(tenantId: UUID, issueIds: Collection<UUID>): Map<UUID, FindingReviewSummary> =
        scaIssueReviewRepository.findLatestByIssueIds(tenantId, issueIds)
            .mapValues { it.value.toSummary() }

    fun recordHumanReview(
        principal: SecruxPrincipal,
        issue: ScaIssue,
        statusAfter: FindingStatus
    ): FindingReviewSummary {
        val now = OffsetDateTime.now(clock)
        val reviewer = principal.username ?: principal.email ?: principal.userId.toString()
        val record =
            ScaIssueReviewRecord(
                reviewId = UUID.randomUUID(),
                tenantId = principal.tenantId,
                issueId = issue.issueId,
                reviewType = REVIEW_TYPE_HUMAN,
                reviewer = reviewer,
                reviewerUserId = principal.userId,
                jobId = null,
                verdict = statusAfter.name,
                reason = null,
                confidence = null,
                statusBefore = issue.status,
                statusAfter = statusAfter,
                payload = null,
                createdAt = now,
                appliedAt = now,
                updatedAt = now,
            )
        scaIssueReviewRepository.insert(record)
        return record.toSummary()
    }

    fun recordAiReviewQueued(
        tenantId: UUID,
        issue: ScaIssue,
        jobId: String
    ) {
        val now = OffsetDateTime.now(clock)
        val record =
            ScaIssueReviewRecord(
                reviewId = UUID.randomUUID(),
                tenantId = tenantId,
                issueId = issue.issueId,
                reviewType = REVIEW_TYPE_AI,
                reviewer = "AI",
                reviewerUserId = null,
                jobId = jobId,
                verdict = "QUEUED",
                reason = null,
                confidence = null,
                statusBefore = issue.status,
                statusAfter = null,
                payload = null,
                createdAt = now,
                appliedAt = null,
                updatedAt = now,
            )
        scaIssueReviewRepository.insert(record)
    }

    fun applyAiReviewIfReady(tenantId: UUID, ticket: AiJobTicket): FindingStatus? {
        if (ticket.jobType != AiJobType.SCA_ISSUE_REVIEW) return null
        val existing = scaIssueReviewRepository.findByJobId(tenantId, ticket.jobId)
        if (existing?.appliedAt != null) {
            return existing.statusAfter
        }

        if (ticket.status == AiJobStatus.FAILED) {
            val now = OffsetDateTime.now(clock)
            val updatedRows =
                scaIssueReviewRepository.updateByJobId(
                    tenantId = tenantId,
                    jobId = ticket.jobId,
                    verdict = "FAILED",
                    reason = ticket.error,
                    confidence = null,
                    statusBefore = existing?.statusBefore,
                    statusAfter = null,
                    payload = buildAiReviewFailurePayload(ticket.error),
                    appliedAt = now,
                    updatedAt = now
                )
            if (updatedRows == 0) {
                val issueId = runCatching { UUID.fromString(ticket.targetId) }.getOrNull()
                val issue = issueId?.let { scaIssueRepository.findById(tenantId, it) }
                if (issue == null) {
                    return null
                }
                val record =
                    ScaIssueReviewRecord(
                        reviewId = UUID.randomUUID(),
                        tenantId = tenantId,
                        issueId = issue.issueId,
                        reviewType = REVIEW_TYPE_AI,
                        reviewer = "AI",
                        reviewerUserId = null,
                        jobId = ticket.jobId,
                        verdict = "FAILED",
                        reason = ticket.error,
                        confidence = null,
                        statusBefore = issue.status,
                        statusAfter = null,
                        payload = buildAiReviewFailurePayload(ticket.error),
                        createdAt = now,
                        appliedAt = now,
                        updatedAt = now,
                    )
                scaIssueReviewRepository.insert(record)
                return issue.status
            }
            return existing?.statusBefore
        }

        if (ticket.status != AiJobStatus.COMPLETED) return null
        val issueId = runCatching { UUID.fromString(ticket.targetId) }.getOrNull() ?: return null

        val issue = scaIssueRepository.findById(tenantId, issueId) ?: return null
        val parsed = parseAiReviewResult(ticket)
        val suggestedStatus = normalizeAiSuggestedStatus(parsed.suggestedStatus)
        val shouldAutoApply = autoApply && issue.status == FindingStatus.OPEN
        val appliedStatus = if (shouldAutoApply) suggestedStatus else null
        val statusAfter = appliedStatus ?: issue.status

        if (appliedStatus != null && appliedStatus != issue.status) {
            scaIssueRepository.updateStatus(tenantId, issueId, appliedStatus)
        }

        val now = OffsetDateTime.now(clock)
        val updatedRows =
            scaIssueReviewRepository.updateByJobId(
                tenantId = tenantId,
                jobId = ticket.jobId,
                verdict = parsed.verdict ?: "DONE",
                reason = parsed.reason,
                confidence = parsed.confidence,
                statusBefore = existing?.statusBefore ?: issue.status,
                statusAfter = appliedStatus,
                payload = parsed.payload,
                appliedAt = now,
                updatedAt = now
            )
        if (updatedRows == 0) {
            val record =
                ScaIssueReviewRecord(
                    reviewId = UUID.randomUUID(),
                    tenantId = tenantId,
                    issueId = issueId,
                    reviewType = REVIEW_TYPE_AI,
                    reviewer = "AI",
                    reviewerUserId = null,
                    jobId = ticket.jobId,
                    verdict = parsed.verdict ?: "DONE",
                    reason = parsed.reason,
                    confidence = parsed.confidence,
                    statusBefore = issue.status,
                    statusAfter = appliedStatus,
                    payload = parsed.payload,
                    createdAt = now,
                    appliedAt = now,
                    updatedAt = now,
                )
            scaIssueReviewRepository.insert(record)
        }

        return statusAfter
    }
}

private fun ScaIssueReviewRecord.toSummary(): FindingReviewSummary =
    buildReviewSummary(
        reviewType = reviewType,
        reviewer = reviewer,
        verdict = verdict,
        confidence = confidence,
        statusBefore = statusBefore,
        statusAfter = statusAfter,
        payload = payload,
        createdAt = createdAt,
        appliedAt = appliedAt
    )
