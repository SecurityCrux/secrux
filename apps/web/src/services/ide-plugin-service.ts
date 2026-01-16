import { apiClient } from "@/lib/api-client"
import type { ApiResponse, IntellijTokenCreatedResponse, IntellijTokenSummary } from "@/types/api"

export type CreateIntellijTokenPayload = {
  name?: string | null
}

export async function createIntellijToken(payload: CreateIntellijTokenPayload) {
  const { data } = await apiClient.post<ApiResponse<IntellijTokenCreatedResponse>>("/ideplugins/intellij/tokens", payload)
  return data.data
}

export async function listIntellijTokens() {
  const { data } = await apiClient.get<ApiResponse<IntellijTokenSummary[]>>("/ideplugins/intellij/tokens")
  return data.data ?? []
}

export async function revokeIntellijToken(tokenId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/ideplugins/intellij/tokens/${tokenId}`)
}

