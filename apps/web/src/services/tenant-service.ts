import { apiClient } from "@/lib/api-client"
import type { ApiResponse, TenantResponse, TenantUpdatePayload } from "@/types/api"

export async function fetchTenant() {
  const { data } = await apiClient.get<ApiResponse<TenantResponse>>("/tenant")
  return data.data
}

export async function updateTenant(payload: TenantUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<TenantResponse>>("/tenant", payload)
  return data.data
}

