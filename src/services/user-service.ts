import { apiClient } from "@/lib/api-client"
import type {
  ApiResponse,
  UserCreatePayload,
  UserDetailResponse,
  UserListResponse,
  UserPasswordResetPayload,
  UserProfileUpdatePayload,
  UserRoleAssignmentPayload,
  UserRoleAssignmentResponse,
  UserStatusUpdatePayload,
  UserSummary,
} from "@/types/api"

export async function listUsers(params: { search?: string; limit?: number; offset?: number } = {}) {
  const { data } = await apiClient.get<ApiResponse<UserListResponse>>("/users", { params })
  return (
    data.data ?? {
      items: [],
      total: 0,
      limit: params.limit ?? 20,
      offset: params.offset ?? 0,
    }
  )
}

export async function createUser(payload: UserCreatePayload) {
  const { data } = await apiClient.post<ApiResponse<UserSummary>>("/users", payload)
  return data.data
}

export async function updateUserStatus(userId: string, payload: UserStatusUpdatePayload) {
  await apiClient.patch<ApiResponse<unknown>>(`/users/${userId}/status`, payload)
}

export async function resetUserPassword(userId: string, payload: UserPasswordResetPayload) {
  await apiClient.post<ApiResponse<unknown>>(`/users/${userId}/reset-password`, payload)
}

export async function getUser(userId: string) {
  const { data } = await apiClient.get<ApiResponse<UserDetailResponse>>(`/users/${userId}`)
  return data.data
}

export async function updateUserProfile(userId: string, payload: UserProfileUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<UserDetailResponse>>(`/users/${userId}`, payload)
  return data.data
}

export async function assignUserRoles(userId: string, payload: UserRoleAssignmentPayload) {
  const { data } = await apiClient.put<ApiResponse<UserRoleAssignmentResponse>>(`/users/${userId}/roles`, payload)
  return data.data
}
