import { apiClient } from "@/lib/api-client"
import type {
  ApiResponse,
  IamRoleCreatePayload,
  IamRoleDetailResponse,
  IamRolePermissionsUpdatePayload,
  IamRoleSummaryResponse,
  IamRoleUpdatePayload,
  PageResponse,
} from "@/types/api"

export async function listIamRoles(params: { search?: string; limit?: number; offset?: number } = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<IamRoleSummaryResponse>>>("/iam/roles", { params })
  return (
    data.data ?? {
      items: [],
      total: 0,
      limit: params.limit ?? 50,
      offset: params.offset ?? 0,
    }
  )
}

export async function getIamRole(roleId: string) {
  const { data } = await apiClient.get<ApiResponse<IamRoleDetailResponse>>(`/iam/roles/${roleId}`)
  return data.data
}

export async function createIamRole(payload: IamRoleCreatePayload) {
  const { data } = await apiClient.post<ApiResponse<IamRoleDetailResponse>>("/iam/roles", payload)
  return data.data
}

export async function updateIamRole(roleId: string, payload: IamRoleUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<IamRoleSummaryResponse>>(`/iam/roles/${roleId}`, payload)
  return data.data
}

export async function setIamRolePermissions(roleId: string, payload: IamRolePermissionsUpdatePayload) {
  const { data } = await apiClient.put<ApiResponse<{ roleId: string; permissions: string[] }>>(
    `/iam/roles/${roleId}/permissions`,
    payload
  )
  return data.data
}

export async function deleteIamRole(roleId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/iam/roles/${roleId}`)
}

