package com.secrux.ai

import org.springframework.stereotype.Component

data class AiReviewRequest(
    val tenantId: String,
    val jobType: AiJobType = AiJobType.STAGE_REVIEW,
    val targetId: String,
    val payload: Map<String, Any?> = emptyMap(),
    val context: Map<String, Any?> = emptyMap(),
    val agent: String? = null
)

@Component
class AiClient(
    private val aiServiceClient: AiServiceClient
) {

    fun review(request: AiReviewRequest): AiJobTicket =
        aiServiceClient.submitReview(
            AiJobSubmissionRequest(
                tenantId = request.tenantId,
                jobType = request.jobType,
                targetId = request.targetId,
                payload = request.payload,
                context = request.context,
                agent = request.agent
            )
        )

    fun fetch(jobId: String): AiJobTicket =
        aiServiceClient.fetchJob(jobId)
}
