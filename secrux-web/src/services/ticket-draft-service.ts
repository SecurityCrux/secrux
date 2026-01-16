import { apiClient } from "@/lib/api-client"
import type {
  ApiResponse,
  AiJobTicket,
  TicketDraftAiApplyPayload,
  TicketDraftAiPayload,
  TicketDraftDetail,
  TicketDraftItemsPayload,
  TicketDraftUpdatePayload,
} from "@/types/api"

export async function getCurrentTicketDraft() {
  const { data } = await apiClient.get<ApiResponse<TicketDraftDetail>>("/ticket-drafts/current")
  return data.data
}

export async function addItemsToTicketDraft(payload: TicketDraftItemsPayload) {
  const { data } = await apiClient.post<ApiResponse<TicketDraftDetail>>("/ticket-drafts/current/items", payload)
  return data.data
}

export async function removeItemsFromTicketDraft(payload: TicketDraftItemsPayload) {
  const { data } = await apiClient.delete<ApiResponse<TicketDraftDetail>>("/ticket-drafts/current/items", { data: payload })
  return data.data
}

export async function clearTicketDraft() {
  const { data } = await apiClient.post<ApiResponse<TicketDraftDetail>>("/ticket-drafts/current/clear")
  return data.data
}

export async function updateTicketDraft(payload: TicketDraftUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<TicketDraftDetail>>("/ticket-drafts/current", payload)
  return data.data
}

export async function generateTicketDraftAi(payload: TicketDraftAiPayload) {
  const { data } = await apiClient.post<ApiResponse<AiJobTicket>>("/ticket-drafts/current/ai-generate", payload)
  return data.data
}

export async function polishTicketDraftAi(payload: TicketDraftAiPayload) {
  const { data } = await apiClient.post<ApiResponse<AiJobTicket>>("/ticket-drafts/current/ai-polish", payload)
  return data.data
}

export async function applyTicketDraftAi(payload: TicketDraftAiApplyPayload) {
  const { data } = await apiClient.post<ApiResponse<TicketDraftDetail>>("/ticket-drafts/current/ai-apply", payload)
  return data.data
}
