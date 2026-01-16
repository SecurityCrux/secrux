import { apiClient } from "@/lib/api-client"
import type {
  AiAgentConfig,
  AiAgentConfigPayload,
  AiAgentUploadPayload,
  AiKnowledgeEntry,
  AiKnowledgeEntryPayload,
  AiKnowledgeSearchHit,
  AiKnowledgeSearchPayload,
  AiMcpConfig,
  AiMcpConfigPayload,
  AiMcpUploadPayload,
  ApiResponse,
} from "@/types/api"

export async function listAiMcps() {
  const { data } = await apiClient.get<ApiResponse<AiMcpConfig[]>>("/ai/mcps")
  return data.data ?? []
}

export async function createAiMcp(payload: AiMcpConfigPayload) {
  const { data } = await apiClient.post<ApiResponse<AiMcpConfig>>("/ai/mcps", payload)
  return data.data
}

export async function updateAiMcp(profileId: string, payload: AiMcpConfigPayload) {
  const { data } = await apiClient.put<ApiResponse<AiMcpConfig>>(`/ai/mcps/${profileId}`, payload)
  return data.data
}

export async function deleteAiMcp(profileId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/ai/mcps/${profileId}`)
}

export async function uploadAiMcp(payload: AiMcpUploadPayload) {
  const form = new FormData()
  form.append("name", payload.name)
  if (payload.entrypoint) {
    form.append("entrypoint", payload.entrypoint)
  }
  form.append("enabled", String(payload.enabled))
  form.append("params", JSON.stringify(payload.params ?? {}))
  form.append("file", payload.file)
  const { data } = await apiClient.post<ApiResponse<AiMcpConfig>>("/ai/mcps/upload", form, {
    headers: { "Content-Type": "multipart/form-data" },
  })
  return data.data
}

export async function uploadAiAgent(payload: AiAgentUploadPayload) {
  const form = new FormData()
  form.append("name", payload.name)
  form.append("kind", payload.kind)
  if (payload.entrypoint) {
    form.append("entrypoint", payload.entrypoint)
  }
  form.append("stageTypes", JSON.stringify(payload.stageTypes ?? []))
  if (payload.mcpProfileId) {
    form.append("mcpProfileId", payload.mcpProfileId)
  }
  form.append("enabled", String(payload.enabled))
  form.append("params", JSON.stringify(payload.params ?? {}))
  form.append("file", payload.file)
  const { data } = await apiClient.post<ApiResponse<AiAgentConfig>>("/ai/agents/upload", form, {
    headers: { "Content-Type": "multipart/form-data" },
  })
  return data.data
}

export async function listAiAgents() {
  const { data } = await apiClient.get<ApiResponse<AiAgentConfig[]>>("/ai/agents")
  return data.data ?? []
}

export async function createAiAgent(payload: AiAgentConfigPayload) {
  const { data } = await apiClient.post<ApiResponse<AiAgentConfig>>("/ai/agents", payload)
  return data.data
}

export async function updateAiAgent(agentId: string, payload: AiAgentConfigPayload) {
  const { data } = await apiClient.put<ApiResponse<AiAgentConfig>>(`/ai/agents/${agentId}`, payload)
  return data.data
}

export async function deleteAiAgent(agentId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/ai/agents/${agentId}`)
}

export async function listAiKnowledgeEntries() {
  const { data } = await apiClient.get<ApiResponse<AiKnowledgeEntry[]>>("/ai/knowledge")
  return data.data ?? []
}

export async function createAiKnowledgeEntry(payload: AiKnowledgeEntryPayload) {
  const { data } = await apiClient.post<ApiResponse<AiKnowledgeEntry>>("/ai/knowledge", payload)
  return data.data
}

export async function updateAiKnowledgeEntry(entryId: string, payload: AiKnowledgeEntryPayload) {
  const { data } = await apiClient.put<ApiResponse<AiKnowledgeEntry>>(`/ai/knowledge/${entryId}`, payload)
  return data.data
}

export async function deleteAiKnowledgeEntry(entryId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/ai/knowledge/${entryId}`)
}

export async function searchAiKnowledge(payload: AiKnowledgeSearchPayload) {
  const { data } = await apiClient.post<ApiResponse<AiKnowledgeSearchHit[]>>("/ai/knowledge/search", payload)
  return data.data ?? []
}
