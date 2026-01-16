import { apiClient } from "@/lib/api-client"
import type {
  ApiResponse,
  ArtifactSummary,
  CreateTaskPayload,
  FindingListFilters,
  FindingSummary,
  PageResponse,
  ResultReviewRequest,
  StageSummary,
  TaskAssignPayload,
  TaskListFilters,
  TaskLogChunkResponse,
  TaskSummary,
} from "@/types/api"

export async function listTasks(params: TaskListFilters = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<TaskSummary>>>("/tasks", {
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

export async function getTaskStages(taskId: string) {
  const { data } = await apiClient.get<ApiResponse<StageSummary[]>>(`/tasks/${taskId}/stages`)
  return data.data ?? []
}

export async function getTaskArtifacts(taskId: string) {
  const { data } = await apiClient.get<ApiResponse<ArtifactSummary[]>>(`/tasks/${taskId}/artifacts`)
  return data.data ?? []
}

export async function getTaskFindings(taskId: string, filters: FindingListFilters = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<FindingSummary>>>(`/tasks/${taskId}/findings`, {
    params: filters,
  })
  return (
    data.data ?? {
      items: [],
      total: 0,
      limit: filters.limit ?? 50,
      offset: filters.offset ?? 0,
    }
  )
}

export async function createTask(payload: CreateTaskPayload) {
  const { data } = await apiClient.post<ApiResponse<TaskSummary>>("/tasks", payload)
  return data.data
}

export async function assignTask(taskId: string, payload: TaskAssignPayload) {
  const { data } = await apiClient.post<ApiResponse<TaskSummary>>(`/tasks/${taskId}/assign`, payload)
  return data.data
}

export async function listTaskLogs(taskId: string, afterSequence?: number) {
  const params: Record<string, number> = {}
  if (afterSequence && afterSequence > 0) {
    params.afterSequence = afterSequence
  }
  const { data } = await apiClient.get<ApiResponse<TaskLogChunkResponse[]>>(`/tasks/${taskId}/logs`, {
    params,
  })
  return data.data ?? []
}

export async function getStageLogs(taskId: string, stageId: string, limit = 200) {
  const { data } = await apiClient.get<ApiResponse<TaskLogChunkResponse[]>>(
    `/tasks/${taskId}/stages/${stageId}/logs`,
    {
      params: { limit },
    }
  )
  return data.data ?? []
}

export async function runResultReviewStage(taskId: string, stageId: string, payload: ResultReviewRequest) {
  const { data } = await apiClient.post<ApiResponse<StageSummary>>(`/tasks/${taskId}/stages/${stageId}/result-review`, payload)
  return data.data
}

export async function deleteTask(taskId: string) {
  await apiClient.delete<ApiResponse<unknown>>(`/tasks/${taskId}`)
}
