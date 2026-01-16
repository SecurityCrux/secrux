package com.secrux.service

import com.secrux.ai.AiServiceClient
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
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class AiIntegrationService(
    private val aiServiceClient: AiServiceClient
) {

    fun listMcps(tenantId: UUID): List<AiMcpConfigResponse> =
        aiServiceClient.listMcps(tenantId)

    fun createMcp(tenantId: UUID, request: AiMcpConfigRequest): AiMcpConfigResponse =
        aiServiceClient.createMcp(tenantId, request)

    fun updateMcp(tenantId: UUID, profileId: UUID, request: AiMcpConfigRequest): AiMcpConfigResponse =
        aiServiceClient.updateMcp(tenantId, profileId, request)

    fun deleteMcp(tenantId: UUID, profileId: UUID) =
        aiServiceClient.deleteMcp(tenantId, profileId)

    fun uploadMcp(tenantId: UUID, request: AiMcpUploadRequest, file: MultipartFile): AiMcpConfigResponse =
        aiServiceClient.uploadMcp(tenantId, request, file)

    fun listAgents(tenantId: UUID): List<AiAgentConfigResponse> =
        aiServiceClient.listAgents(tenantId)

    fun createAgent(tenantId: UUID, request: AiAgentConfigRequest): AiAgentConfigResponse =
        aiServiceClient.createAgent(tenantId, request)

    fun updateAgent(tenantId: UUID, agentId: UUID, request: AiAgentConfigRequest): AiAgentConfigResponse =
        aiServiceClient.updateAgent(tenantId, agentId, request)

    fun deleteAgent(tenantId: UUID, agentId: UUID) =
        aiServiceClient.deleteAgent(tenantId, agentId)

    fun uploadAgent(tenantId: UUID, request: AiAgentUploadRequest, file: MultipartFile): AiAgentConfigResponse =
        aiServiceClient.uploadAgent(tenantId, request, file)

    fun listKnowledgeEntries(tenantId: UUID): List<AiKnowledgeEntryResponse> =
        aiServiceClient.listKnowledgeEntries(tenantId)

    fun createKnowledgeEntry(tenantId: UUID, request: AiKnowledgeEntryRequest): AiKnowledgeEntryResponse =
        aiServiceClient.createKnowledgeEntry(tenantId, request)

    fun updateKnowledgeEntry(tenantId: UUID, entryId: UUID, request: AiKnowledgeEntryRequest): AiKnowledgeEntryResponse =
        aiServiceClient.updateKnowledgeEntry(tenantId, entryId, request)

    fun deleteKnowledgeEntry(tenantId: UUID, entryId: UUID) =
        aiServiceClient.deleteKnowledgeEntry(tenantId, entryId)

    fun searchKnowledge(tenantId: UUID, request: AiKnowledgeSearchRequest): List<AiKnowledgeSearchHit> =
        aiServiceClient.searchKnowledge(tenantId, request)
}
