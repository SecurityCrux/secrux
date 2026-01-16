import { apiClient } from "@/lib/api-client"
import type { ApiResponse, RuleResponse, RuleUpsertPayload } from "@/types/api"

export async function listRules() {
  const { data } = await apiClient.get<ApiResponse<RuleResponse[]>>("/rules")
  return data.data ?? []
}

export async function createRule(payload: RuleUpsertPayload) {
  await apiClient.post<ApiResponse<RuleResponse>>("/rules", payload)
}

