package com.secrux.service

import com.secrux.ai.AiJobTicket
import com.secrux.ai.AiJobType
import com.secrux.ai.AiJobStatus
import com.secrux.domain.Finding
import com.secrux.domain.FindingStatus
import com.secrux.dto.FindingReviewSummary
import com.secrux.repo.AiReviewRecord
import com.secrux.repo.AiReviewRepository
import com.secrux.repo.FindingRepository
import com.secrux.security.SecruxPrincipal
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@Service
class FindingReviewService(
    private val findingRepository: FindingRepository,
    private val aiReviewRepository: AiReviewRepository,
    private val clock: Clock
) {

    fun latestReview(tenantId: UUID, findingId: UUID): FindingReviewSummary? =
        aiReviewRepository.findLatestByFindingId(tenantId, findingId)?.toSummary()

    fun latestReviews(tenantId: UUID, findingIds: Collection<UUID>): Map<UUID, FindingReviewSummary> =
        aiReviewRepository.findLatestByFindingIds(tenantId, findingIds)
            .mapValues { it.value.toSummary() }

    fun recordHumanReview(
        principal: SecruxPrincipal,
        finding: Finding,
        statusAfter: FindingStatus,
        reason: String?
    ): FindingReviewSummary {
        val now = OffsetDateTime.now(clock)
        val reviewer = principal.username ?: principal.email ?: principal.userId.toString()
        val record = AiReviewRecord(
            reviewId = UUID.randomUUID(),
            tenantId = principal.tenantId,
            findingId = finding.findingId,
            reviewType = REVIEW_TYPE_HUMAN,
            reviewer = reviewer,
            reviewerUserId = principal.userId,
            jobId = null,
            verdict = statusAfter.name,
            reason = reason,
            confidence = null,
            statusBefore = finding.status,
            statusAfter = statusAfter,
            payload = null,
            createdAt = now,
            appliedAt = now,
            updatedAt = now,
        )
        aiReviewRepository.insert(record)
        return record.toSummary()
    }

    fun recordAiReviewQueued(
        tenantId: UUID,
        finding: Finding,
        jobId: String
    ) {
        val now = OffsetDateTime.now(clock)
        val record = AiReviewRecord(
            reviewId = UUID.randomUUID(),
            tenantId = tenantId,
            findingId = finding.findingId,
            reviewType = REVIEW_TYPE_AI,
            reviewer = "AI",
            reviewerUserId = null,
            jobId = jobId,
            verdict = "QUEUED",
            reason = null,
            confidence = null,
            statusBefore = finding.status,
            statusAfter = null,
            payload = null,
            createdAt = now,
            appliedAt = null,
            updatedAt = now,
        )
        aiReviewRepository.insert(record)
    }

    fun applyAiReviewIfReady(tenantId: UUID, ticket: AiJobTicket): FindingStatus? {
        if (ticket.jobType != AiJobType.FINDING_REVIEW) return null
        val existing = aiReviewRepository.findByJobId(tenantId, ticket.jobId)
        if (existing?.appliedAt != null) {
            return existing.statusAfter
        }

        if (ticket.status == AiJobStatus.FAILED) {
            val now = OffsetDateTime.now(clock)
            val updatedRows = aiReviewRepository.updateByJobId(
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
                val findingId = runCatching { UUID.fromString(ticket.targetId) }.getOrNull()
                val finding = findingId?.let { findingRepository.findById(it, tenantId) }
                if (finding == null) {
                    return null
                }
                val record = AiReviewRecord(
                    reviewId = UUID.randomUUID(),
                    tenantId = tenantId,
                    findingId = finding.findingId,
                    reviewType = REVIEW_TYPE_AI,
                    reviewer = "AI",
                    reviewerUserId = null,
                    jobId = ticket.jobId,
                    verdict = "FAILED",
                    reason = ticket.error,
                    confidence = null,
                    statusBefore = finding.status,
                    statusAfter = null,
                    payload = buildAiReviewFailurePayload(ticket.error),
                    createdAt = now,
                    appliedAt = now,
                    updatedAt = now,
                )
                aiReviewRepository.insert(record)
                return finding.status
            }
            return existing?.statusBefore
        }

        if (ticket.status != AiJobStatus.COMPLETED) return null
        val findingId = runCatching { UUID.fromString(ticket.targetId) }.getOrNull() ?: return null

        val finding = findingRepository.findById(findingId, tenantId) ?: return null
        val parsed = parseAiReviewResult(ticket)
        val suggestedStatus = normalizeAiSuggestedStatus(parsed.suggestedStatus)
        val shouldAutoApply = finding.status == FindingStatus.OPEN
        val appliedStatus = if (shouldAutoApply) suggestedStatus else null
        val statusAfter = appliedStatus ?: finding.status

        if (appliedStatus != null && appliedStatus != finding.status) {
            findingRepository.updateStatus(
                findingId = findingId,
                tenantId = tenantId,
                status = appliedStatus,
                fixVersion = null
            )
        }

        val now = OffsetDateTime.now(clock)
        val updatedRows = aiReviewRepository.updateByJobId(
            tenantId = tenantId,
            jobId = ticket.jobId,
            verdict = parsed.verdict ?: "DONE",
            reason = parsed.reason,
            confidence = parsed.confidence,
            statusBefore = existing?.statusBefore ?: finding.status,
            statusAfter = appliedStatus,
            payload = parsed.payload,
            appliedAt = now,
            updatedAt = now
        )
        if (updatedRows == 0) {
            val record = AiReviewRecord(
                reviewId = UUID.randomUUID(),
                tenantId = tenantId,
                findingId = findingId,
                reviewType = REVIEW_TYPE_AI,
                reviewer = "AI",
                reviewerUserId = null,
                jobId = ticket.jobId,
                verdict = parsed.verdict ?: "DONE",
                reason = parsed.reason,
                confidence = parsed.confidence,
                statusBefore = finding.status,
                statusAfter = appliedStatus,
                payload = parsed.payload,
                createdAt = now,
                appliedAt = now,
                updatedAt = now,
            )
            aiReviewRepository.insert(record)
        }

        return statusAfter
    }
}

private fun AiReviewRecord.toSummary(): FindingReviewSummary {
    return buildReviewSummary(
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
}
