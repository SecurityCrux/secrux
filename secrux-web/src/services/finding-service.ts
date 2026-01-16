import type {
  ApiResponse,
  AiJobTicket,
  FindingBatchStatusUpdatePayload,
  FindingBatchStatusUpdateResponse,
  CodeSnippet,
  FindingDetail,
  FindingListFilters,
  FindingStatusUpdatePayload,
  FindingSummary,
  PageResponse,
} from "@/types/api"
import { apiClient } from "@/lib/api-client"

export async function updateFindingStatus(findingId: string, payload: FindingStatusUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<FindingSummary>>(`/findings/${findingId}`, payload)
  return data.data
}

export async function updateFindingStatusBatch(payload: FindingBatchStatusUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<FindingBatchStatusUpdateResponse>>(`/findings/batch/status`, payload)
  return data.data
}

export async function triggerFindingAiReview(findingId: string) {
  const { data } = await apiClient.post<ApiResponse<AiJobTicket>>(`/findings/${findingId}/ai-review`)
  return data.data
}

export async function getFindingDetail(findingId: string) {
  const { data } = await apiClient.get<ApiResponse<FindingDetail>>(`/findings/${findingId}`)
  return data.data
}

export async function getFindingSnippet(
  findingId: string,
  params: { path: string; line: number; context?: number }
) {
  const { data } = await apiClient.get<ApiResponse<CodeSnippet | null>>(`/findings/${findingId}/snippet`, { params })
  return data.data
}

export async function listFindingsByProject(projectId: string, params: FindingListFilters = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<FindingSummary>>>(`/projects/${projectId}/findings`, {
    params,
  })
  return (
    data.data ?? {
      items: [],
      total: 0,
      limit: params.limit ?? 50,
      offset: params.offset ?? 0,
    }
  )
}

export async function listFindingsByRepo(projectId: string, repoId: string, params: FindingListFilters = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<FindingSummary>>>(
    `/projects/${projectId}/repos/${repoId}/findings`,
    {
      params,
    }
  )
  return (
    data.data ?? {
      items: [],
      total: 0,
      limit: params.limit ?? 50,
      offset: params.offset ?? 0,
    }
  )
}
