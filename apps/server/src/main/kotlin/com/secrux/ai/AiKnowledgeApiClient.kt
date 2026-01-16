package com.secrux.ai

import com.secrux.dto.AiKnowledgeEntryRequest
import com.secrux.dto.AiKnowledgeEntryResponse
import com.secrux.dto.AiKnowledgeSearchHit
import com.secrux.dto.AiKnowledgeSearchRequest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

internal class AiKnowledgeApiClient(
    private val aiServiceWebClient: WebClient,
    private val callSupport: AiServiceCallSupport
) {

    private val knowledgeListType = object : ParameterizedTypeReference<KnowledgeListPayload>() {}
    private val knowledgeSearchType = object : ParameterizedTypeReference<KnowledgeSearchPayload>() {}

    fun listKnowledgeEntries(tenantId: UUID): List<AiKnowledgeEntryResponse> =
        callSupport.blockingCall("GET /api/v1/knowledge") {
            aiServiceWebClient.get()
                .uri { builder ->
                    builder.path("/api/v1/knowledge")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .retrieve()
                .bodyToMono(knowledgeListType)
                .map { it.data }
                .block()
        }

    fun createKnowledgeEntry(
        tenantId: UUID,
        request: AiKnowledgeEntryRequest
    ): AiKnowledgeEntryResponse =
        callSupport.blockingCall("POST /api/v1/knowledge") {
            aiServiceWebClient.post()
                .uri { builder ->
                    builder.path("/api/v1/knowledge")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiKnowledgeEntryResponse::class.java)
                .block()
        }

    fun updateKnowledgeEntry(
        tenantId: UUID,
        entryId: UUID,
        request: AiKnowledgeEntryRequest
    ): AiKnowledgeEntryResponse =
        callSupport.blockingCall("PUT /api/v1/knowledge/{entryId}") {
            aiServiceWebClient.put()
                .uri { builder ->
                    builder.path("/api/v1/knowledge/{entryId}")
                        .queryParam("tenantId", tenantId)
                        .build(entryId)
                }
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiKnowledgeEntryResponse::class.java)
                .block()
        }

    fun deleteKnowledgeEntry(tenantId: UUID, entryId: UUID) {
        callSupport.blockingUnitCall("DELETE /api/v1/knowledge/{entryId}") {
            aiServiceWebClient.delete()
                .uri { builder ->
                    builder.path("/api/v1/knowledge/{entryId}")
                        .queryParam("tenantId", tenantId)
                        .build(entryId)
                }
                .retrieve()
                .toBodilessEntity()
                .block()
        }
    }

    fun searchKnowledge(
        tenantId: UUID,
        request: AiKnowledgeSearchRequest
    ): List<AiKnowledgeSearchHit> {
        val payload = mapOf(
            "tenantId" to tenantId.toString(),
            "query" to request.query,
            "limit" to request.limit.coerceIn(1, 20),
            "tags" to request.tags
        )
        return callSupport.blockingCall("POST /api/v1/knowledge/search") {
            aiServiceWebClient.post()
                .uri("/api/v1/knowledge/search")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(knowledgeSearchType)
                .map { it.data }
                .block()
        }
    }
}

