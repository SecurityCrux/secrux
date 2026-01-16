package com.secrux.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.dto.AiMcpConfigRequest
import com.secrux.dto.AiMcpConfigResponse
import com.secrux.dto.AiMcpUploadRequest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

internal class AiMcpApiClient(
    private val aiServiceWebClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val callSupport: AiServiceCallSupport
) {

    private val mcpListType = object : ParameterizedTypeReference<McpListPayload>() {}

    fun listMcps(tenantId: UUID): List<AiMcpConfigResponse> =
        callSupport.blockingCall("GET /api/v1/mcps") {
            aiServiceWebClient.get()
                .uri { builder ->
                    builder.path("/api/v1/mcps")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .retrieve()
                .bodyToMono(mcpListType)
                .map { it.data }
                .block()
        }

    fun createMcp(tenantId: UUID, request: AiMcpConfigRequest): AiMcpConfigResponse =
        callSupport.blockingCall("POST /api/v1/mcps") {
            aiServiceWebClient.post()
                .uri { builder ->
                    builder.path("/api/v1/mcps")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiMcpConfigResponse::class.java)
                .block()
        }

    fun updateMcp(
        tenantId: UUID,
        profileId: UUID,
        request: AiMcpConfigRequest
    ): AiMcpConfigResponse =
        callSupport.blockingCall("PUT /api/v1/mcps/{profileId}") {
            aiServiceWebClient.put()
                .uri { builder ->
                    builder.path("/api/v1/mcps/{profileId}")
                        .queryParam("tenantId", tenantId)
                        .build(profileId)
                }
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AiMcpConfigResponse::class.java)
                .block()
        }

    fun deleteMcp(tenantId: UUID, profileId: UUID) {
        callSupport.blockingUnitCall("DELETE /api/v1/mcps/{profileId}") {
            aiServiceWebClient.delete()
                .uri { builder ->
                    builder.path("/api/v1/mcps/{profileId}")
                        .queryParam("tenantId", tenantId)
                        .build(profileId)
                }
                .retrieve()
                .toBodilessEntity()
                .block()
        }
    }

    fun uploadMcp(
        tenantId: UUID,
        request: AiMcpUploadRequest,
        file: MultipartFile
    ): AiMcpConfigResponse {
        val builder = MultipartBodyBuilder()
        builder.part("name", request.name)
        request.entrypoint?.let { builder.part("entrypoint", it) }
        builder.part("enabled", request.enabled.toString())
        val paramsJson = objectMapper.writeValueAsString(request.params)
        builder.part("params", paramsJson)
        val filename = file.originalFilename ?: "archive.zip"
        val mediaType = file.contentType?.let { MediaType.parseMediaType(it) } ?: MediaType.APPLICATION_OCTET_STREAM
        builder.part("file", file.resource)
            .filename(filename)
            .contentType(mediaType)
        return callSupport.blockingCall("POST /api/v1/mcps/upload") {
            aiServiceWebClient.post()
                .uri { uriBuilder ->
                    uriBuilder.path("/api/v1/mcps/upload")
                        .queryParam("tenantId", tenantId)
                        .build()
                }
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(AiMcpConfigResponse::class.java)
                .block()
        }
    }
}

