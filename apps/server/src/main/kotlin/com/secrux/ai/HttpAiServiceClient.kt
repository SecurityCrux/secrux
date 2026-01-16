package com.secrux.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.secrux.dto.AiAgentConfigRequest
import com.secrux.dto.AiAgentConfigResponse
import com.secrux.dto.AiAgentUploadRequest
import com.secrux.dto.AiKnowledgeEntryRequest
import com.secrux.dto.AiKnowledgeEntryResponse
import com.secrux.dto.AiKnowledgeSearchHit
import com.secrux.dto.AiKnowledgeSearchRequest
import com.secrux.dto.AiMcpConfigRequest
import com.secrux.dto.AiMcpConfigResponse
import com.secrux.dto.AiMcpUploadRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

@Component
class HttpAiServiceClient(
    private val aiServiceWebClient: WebClient,
    private val objectMapper: ObjectMapper
) : AiServiceClient {

    private val log = LoggerFactory.getLogger(HttpAiServiceClient::class.java)
    private val callSupport = AiServiceCallSupport(log)
    private val jobApi = AiJobApiClient(aiServiceWebClient, callSupport)
    private val mcpApi = AiMcpApiClient(aiServiceWebClient, objectMapper, callSupport)
    private val agentApi = AiAgentApiClient(aiServiceWebClient, objectMapper, callSupport)
    private val knowledgeApi = AiKnowledgeApiClient(aiServiceWebClient, callSupport)

    override fun submitReview(request: AiJobSubmissionRequest): AiJobTicket =
        jobApi.submitReview(request)

    override fun fetchJob(jobId: String): AiJobTicket =
        jobApi.fetchJob(jobId)

    override fun listMcps(tenantId: UUID): List<AiMcpConfigResponse> =
        mcpApi.listMcps(tenantId)

    override fun createMcp(tenantId: UUID, request: AiMcpConfigRequest): AiMcpConfigResponse =
        mcpApi.createMcp(tenantId, request)

    override fun updateMcp(
        tenantId: UUID,
        profileId: UUID,
        request: AiMcpConfigRequest
    ): AiMcpConfigResponse =
        mcpApi.updateMcp(tenantId, profileId, request)

    override fun deleteMcp(tenantId: UUID, profileId: UUID) {
        mcpApi.deleteMcp(tenantId, profileId)
    }

    override fun uploadMcp(
        tenantId: UUID,
        request: AiMcpUploadRequest,
        file: org.springframework.web.multipart.MultipartFile
    ): AiMcpConfigResponse = mcpApi.uploadMcp(tenantId, request, file)

    override fun uploadAgent(
        tenantId: UUID,
        request: AiAgentUploadRequest,
        file: org.springframework.web.multipart.MultipartFile
    ): AiAgentConfigResponse = agentApi.uploadAgent(tenantId, request, file)

    override fun listAgents(tenantId: UUID): List<AiAgentConfigResponse> =
        agentApi.listAgents(tenantId)

    override fun createAgent(
        tenantId: UUID,
        request: AiAgentConfigRequest
    ): AiAgentConfigResponse =
        agentApi.createAgent(tenantId, request)

    override fun updateAgent(
        tenantId: UUID,
        agentId: UUID,
        request: AiAgentConfigRequest
    ): AiAgentConfigResponse =
        agentApi.updateAgent(tenantId, agentId, request)

    override fun deleteAgent(tenantId: UUID, agentId: UUID) {
        agentApi.deleteAgent(tenantId, agentId)
    }

    override fun listKnowledgeEntries(tenantId: UUID): List<AiKnowledgeEntryResponse> =
        knowledgeApi.listKnowledgeEntries(tenantId)

    override fun createKnowledgeEntry(
        tenantId: UUID,
        request: AiKnowledgeEntryRequest
    ): AiKnowledgeEntryResponse =
        knowledgeApi.createKnowledgeEntry(tenantId, request)

    override fun updateKnowledgeEntry(
        tenantId: UUID,
        entryId: UUID,
        request: AiKnowledgeEntryRequest
    ): AiKnowledgeEntryResponse =
        knowledgeApi.updateKnowledgeEntry(tenantId, entryId, request)

    override fun deleteKnowledgeEntry(tenantId: UUID, entryId: UUID) {
        knowledgeApi.deleteKnowledgeEntry(tenantId, entryId)
    }

    override fun searchKnowledge(
        tenantId: UUID,
        request: AiKnowledgeSearchRequest
    ): List<AiKnowledgeSearchHit> = knowledgeApi.searchKnowledge(tenantId, request)
}
