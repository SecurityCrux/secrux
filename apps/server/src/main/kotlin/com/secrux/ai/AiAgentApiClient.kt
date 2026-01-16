package com.secrux.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.dto.AiAgentConfigRequest
import com.secrux.dto.AiAgentConfigResponse
import com.secrux.dto.AiAgentUploadRequest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

internal class AiAgentApiClient(
    private val aiServiceWebClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val callSupport: AiServiceCallSupport
) {

    private val agentListType = object : ParameterizedTypeReference<AgentListPayload>() {}

    fun uploadAgent(
        tenantId: UUID,
        request: AiAgentUploadRequest,
        file: MultipartFile
    ): AiAgentConfigResponse {
        val builder = MultipartBodyBuilder()
        builder.part("name", request.name)
        builder.part("kind", request.kind)
        request.entrypoint?.let { builder.part("entrypoint", it) }
        builder.part("enabled", request.enabled.toString())
        builder.part("stageTypes", objectMapper.writeValueAsString(request.stageTypes))
        request.mcpProfileId?.let { builder.part("mcpProfileId", it.toString()) }
        builder.part("params", objectMapper.writeValueAsString(request.params))
        val filename = file.originalFilename ?: "agent.zip"
        val mediaType = file.contentType?.let { MediaType.parseMediaType(it) } ?: MediaType.APPLICATION_OCTET_STREAM
        builder.part("file", file.resource)
            .filename(filename)
            .contentType(mediaType)
        return callSupport.blockingCall("POST /api/v1/agents/upload") {
            aiServiceWebClient.post()
                .uri { uriBuilder ->
                    uriBuilder.path("/api/v1/agents/upload")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(AiAgentConfigResponse::class.java)
                .block()
        }
    }

    fun listAgents(tenantId: UUID): List<AiAgentConfigResponse> =
        callSupport.blockingCall("GET /api/v1/agents") {
            aiServiceWebClient.get()
                .uri { builder ->
                    builder.path("/api/v1/agents")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .retrieve()
                .bodyToMono(agentListType)
                .map { it.data }
                .block()
        }

    fun createAgent(
        tenantId: UUID,
        request: AiAgentConfigRequest
    ): AiAgentConfigResponse =
        callSupport.blockingCall("POST /api/v1/agents") {
            aiServiceWebClient.post()
                .uri { builder ->
                    builder.path("/api/v1/agents")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiAgentConfigResponse::class.java)
                .block()
        }

    fun updateAgent(
        tenantId: UUID,
        agentId: UUID,
        request: AiAgentConfigRequest
    ): AiAgentConfigResponse =
        callSupport.blockingCall("PUT /api/v1/agents/{agentId}") {
            aiServiceWebClient.put()
                .uri { builder ->
                    builder.path("/api/v1/agents/{agentId}")
                        .queryParam("tenantId", tenantId)
                        .build(agentId)
                }
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiAgentConfigResponse::class.java)
                .block()
        }

    fun deleteAgent(tenantId: UUID, agentId: UUID) {
        callSupport.blockingUnitCall("DELETE /api/v1/agents/{agentId}") {
            aiServiceWebClient.delete()
                .uri { builder ->
                    builder.path("/api/v1/agents/{agentId}")
                        .queryParam("tenantId", tenantId)
                        .build(agentId)
                }
                .retrieve()
                .toBodilessEntity()
                .block()
        }
    }
}

