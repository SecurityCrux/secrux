import { apiClient } from "@/lib/api-client"
import type { AiClientConfig, AiClientConfigPayload, AiProviderTemplate, ApiResponse } from "@/types/api"

export async function listAiClients() {
  const { data } = await apiClient.get<ApiResponse<AiClientConfig[]>>("/ai-clients")
  return data.data ?? []
}

export async function createAiClient(payload: AiClientConfigPayload) {
  const { data } = await apiClient.post<ApiResponse<AiClientConfig>>("/ai-clients", payload)
  return data.data
}

export async function updateAiClient(configId: string, payload: AiClientConfigPayload) {
  const { data } = await apiClient.put<ApiResponse<AiClientConfig>>(`/ai-clients/${configId}`, payload)
  return data.data
}

export async function deleteAiClient(configId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/ai-clients/${configId}`)
}

export async function listAiProviderTemplates() {
  const { data } = await apiClient.get<ApiResponse<AiProviderTemplate[]>>("/ai-clients/providers")
  return data.data ?? []
}
