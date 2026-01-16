import { useQuery } from "@tanstack/react-query"
import { listAiClients } from "@/services/ai-client-service"
import type { AiClientConfig } from "@/types/api"

export function useAiClientsQuery() {
  return useQuery<AiClientConfig[]>({
    queryKey: ["ai-clients"],
    queryFn: () => listAiClients(),
    staleTime: 30_000,
  })
}

