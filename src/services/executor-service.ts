import { apiClient } from "@/lib/api-client"
import type {
  ApiResponse,
  ExecutorRegisterPayload,
  ExecutorStatus,
  ExecutorSummary,
  ExecutorTokenResponse,
} from "@/types/api"

export async function listExecutors(params?: { status?: ExecutorStatus; search?: string }) {
  const { data } = await apiClient.get<ApiResponse<ExecutorSummary[]>>("/executors", {
    params,
  })
  return data.data ?? []
}

export async function registerExecutor(payload: ExecutorRegisterPayload) {
  const { data } = await apiClient.post<ApiResponse<ExecutorSummary>>("/executors/register", payload)
  return data.data
}

export async function updateExecutorStatus(executorId: string, status: ExecutorStatus) {
  await apiClient.post<ApiResponse<unknown>>(`/executors/${executorId}/status`, undefined, {
    params: { status },
  })
}

export async function getExecutorToken(executorId: string) {
  const { data } = await apiClient.get<ApiResponse<ExecutorTokenResponse>>(`/executors/${executorId}/token`)
  return data.data
}

