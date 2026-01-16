import { apiClient } from "@/lib/api-client"
import type { ApiResponse, AiJobTicket } from "@/types/api"

export async function getAiJob(jobId: string) {
  const { data } = await apiClient.get<ApiResponse<AiJobTicket>>(`/ai/jobs/${jobId}`)
  return data.data
}

