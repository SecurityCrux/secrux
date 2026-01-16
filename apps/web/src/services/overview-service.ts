import { apiClient } from "@/lib/api-client"
import type { ApiResponse, OverviewFindingItem, OverviewSummaryResponse, OverviewTaskItem, PageResponse } from "@/types/api"

export async function getOverviewSummary(params: { window?: string } = {}) {
  const { data } = await apiClient.get<ApiResponse<OverviewSummaryResponse>>("/overview/summary", { params })
  return data.data
}

export async function listOverviewRecentTasks(params: { limit?: number; offset?: number } = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<OverviewTaskItem>>>("/overview/tasks/recent", { params })
  return (
    data.data ?? {
      items: [],
      total: 0,
      limit: params.limit ?? 10,
      offset: params.offset ?? 0,
    }
  )
}

export async function listOverviewTopFindings(params: { limit?: number; offset?: number } = {}) {
  const { data } = await apiClient.get<ApiResponse<PageResponse<OverviewFindingItem>>>("/overview/findings/top", { params })
  return (
    data.data ?? {
      items: [],
      total: 0,
      limit: params.limit ?? 10,
      offset: params.offset ?? 0,
    }
  )
}

