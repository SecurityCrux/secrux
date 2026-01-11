import { apiClient } from "@/lib/api-client"
import type {
  ApiResponse,
  TicketCreateFromDraftPayload,
  TicketCreationPayload,
  TicketProviderTemplate,
  TicketStatusUpdatePayload,
  TicketSummary,
} from "@/types/api"

export async function listTickets(params: {
  projectId?: string
  provider?: string
  status?: string
  search?: string
  limit?: number
  offset?: number
}) {
  const { data } = await apiClient.get<ApiResponse<TicketSummary[]>>("/tickets", { params })
  return data.data ?? []
}

export async function getTicket(ticketId: string) {
  const { data } = await apiClient.get<ApiResponse<TicketSummary>>(`/tickets/${ticketId}`)
  return data.data
}

export async function createTicket(payload: TicketCreationPayload) {
  const { data } = await apiClient.post<ApiResponse<unknown>>("/tickets", payload)
  return data.data
}

export async function createTicketFromDraft(payload: TicketCreateFromDraftPayload) {
  const { data } = await apiClient.post<ApiResponse<unknown>>("/tickets/from-draft", payload)
  return data.data
}

export async function updateTicketStatus(ticketId: string, payload: TicketStatusUpdatePayload) {
  const { data } = await apiClient.patch<ApiResponse<TicketSummary>>(`/tickets/${ticketId}`, payload)
  return data.data
}

export async function listTicketProviders() {
  const { data } = await apiClient.get<ApiResponse<TicketProviderTemplate[]>>("/tickets/providers")
  return data.data ?? []
}
