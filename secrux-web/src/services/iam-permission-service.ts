import { apiClient } from "@/lib/api-client"
import type { ApiResponse, PermissionCatalogResponse } from "@/types/api"

export async function fetchPermissionCatalog() {
  const { data } = await apiClient.get<ApiResponse<PermissionCatalogResponse>>("/iam/permissions")
  return data.data
}

