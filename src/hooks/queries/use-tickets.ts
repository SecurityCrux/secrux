import { useQuery } from "@tanstack/react-query"
import { listTickets } from "@/services/ticket-service"
import type { TicketSummary } from "@/types/api"

type Filters = {
  projectId?: string
  provider?: string
  status?: string
  search?: string
  limit?: number
  offset?: number
}

export function useTicketsQuery(filters: Filters) {
  return useQuery<TicketSummary[]>({
    queryKey: ["tickets", filters],
    queryFn: () => listTickets(filters),
    staleTime: 30_000,
  })
}

