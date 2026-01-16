package com.secrux.ai

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
import java.util.UUID
import org.springframework.web.multipart.MultipartFile

interface AiServiceClient {
    fun submitReview(request: AiJobSubmissionRequest): AiJobTicket
    fun fetchJob(jobId: String): AiJobTicket
    fun listMcps(tenantId: UUID): List<AiMcpConfigResponse>
    fun createMcp(tenantId: UUID, request: AiMcpConfigRequest): AiMcpConfigResponse
    fun updateMcp(tenantId: UUID, profileId: UUID, request: AiMcpConfigRequest): AiMcpConfigResponse
    fun deleteMcp(tenantId: UUID, profileId: UUID)
    fun uploadMcp(tenantId: UUID, request: AiMcpUploadRequest, file: MultipartFile): AiMcpConfigResponse
    fun listAgents(tenantId: UUID): List<AiAgentConfigResponse>
    fun createAgent(tenantId: UUID, request: AiAgentConfigRequest): AiAgentConfigResponse
    fun updateAgent(tenantId: UUID, agentId: UUID, request: AiAgentConfigRequest): AiAgentConfigResponse
    fun deleteAgent(tenantId: UUID, agentId: UUID)
    fun uploadAgent(tenantId: UUID, request: AiAgentUploadRequest, file: MultipartFile): AiAgentConfigResponse
    fun listKnowledgeEntries(tenantId: UUID): List<AiKnowledgeEntryResponse>
    fun createKnowledgeEntry(tenantId: UUID, request: AiKnowledgeEntryRequest): AiKnowledgeEntryResponse
    fun updateKnowledgeEntry(tenantId: UUID, entryId: UUID, request: AiKnowledgeEntryRequest): AiKnowledgeEntryResponse
    fun deleteKnowledgeEntry(tenantId: UUID, entryId: UUID)
    fun searchKnowledge(tenantId: UUID, request: AiKnowledgeSearchRequest): List<AiKnowledgeSearchHit>
}
