package com.secrux.ai

import org.springframework.web.reactive.function.client.WebClient

internal class AiJobApiClient(
    private val aiServiceWebClient: WebClient,
    private val callSupport: AiServiceCallSupport
) {

    fun submitReview(request: AiJobSubmissionRequest): AiJobTicket =
        callSupport.blockingCall("POST /api/v1/jobs/reviews") {
            aiServiceWebClient.post()
                .uri("/api/v1/jobs/reviews")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiJobTicketPayload::class.java)
                .map { it.toDomain() }
                .block()
        }

    fun fetchJob(jobId: String): AiJobTicket =
        callSupport.blockingCall("GET /api/v1/jobs/{jobId}") {
            aiServiceWebClient.get()
                .uri("/api/v1/jobs/{jobId}", jobId)
                .retrieve()
                .bodyToMono(AiJobTicketPayload::class.java)
                .map { it.toDomain() }
                .block()
        }
}

