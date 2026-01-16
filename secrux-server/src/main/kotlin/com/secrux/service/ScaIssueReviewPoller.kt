package com.secrux.service

import com.secrux.ai.AiClient
import com.secrux.repo.ScaIssueReviewRepository
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class ScaIssueReviewPoller(
    private val aiClient: AiClient,
    private val scaIssueReviewRepository: ScaIssueReviewRepository,
    private val scaIssueReviewService: ScaIssueReviewService
) {

    private val log = LoggerFactory.getLogger(ScaIssueReviewPoller::class.java)

    @Scheduled(fixedDelayString = "\${secrux.ai.review.pollDelayMs:3000}")
    fun pollPendingScaIssueReviews() {
        val batchSize = 30
        val pending = scaIssueReviewRepository.listPendingAiJobs(batchSize)
        if (pending.isEmpty()) {
            return
        }
        pending.forEach { job ->
            try {
                val ticket = aiClient.fetch(job.jobId)
                scaIssueReviewService.applyAiReviewIfReady(job.tenantId, ticket)
            } catch (ex: Exception) {
                LogContext.with(LogContext.TENANT_ID to job.tenantId) {
                    log.debug("event=sca_ai_review_poll_failed jobId={} error={}", job.jobId, ex.message, ex)
                }
            }
        }
    }
}

