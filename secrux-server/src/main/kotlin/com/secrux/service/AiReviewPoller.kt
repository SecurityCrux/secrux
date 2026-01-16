package com.secrux.service

import com.secrux.ai.AiClient
import com.secrux.repo.AiReviewRepository
import com.secrux.support.LogContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class AiReviewPoller(
    private val aiClient: AiClient,
    private val aiReviewRepository: AiReviewRepository,
    private val findingReviewService: FindingReviewService
) {

    private val log = LoggerFactory.getLogger(AiReviewPoller::class.java)

    @Scheduled(fixedDelayString = "\${secrux.ai.review.pollDelayMs:3000}")
    fun pollPendingAiReviews() {
        val batchSize = 30
        val pending = aiReviewRepository.listPendingAiJobs(batchSize)
        if (pending.isEmpty()) {
            return
        }
        pending.forEach { job ->
            try {
                val ticket = aiClient.fetch(job.jobId)
                findingReviewService.applyAiReviewIfReady(job.tenantId, ticket)
            } catch (ex: Exception) {
                LogContext.with(LogContext.TENANT_ID to job.tenantId) {
                    log.debug("event=ai_review_poll_failed jobId={} error={}", job.jobId, ex.message, ex)
                }
            }
        }
    }
}
