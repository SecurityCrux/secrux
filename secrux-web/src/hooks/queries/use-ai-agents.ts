import { useQuery } from "@tanstack/react-query"
import { listAiAgents } from "@/services/ai-integration-service"
import type { AiAgentConfig } from "@/types/api"

export function useAiAgentsQuery() {
  return useQuery<AiAgentConfig[]>({
    queryKey: ["ai", "agents"],
    queryFn: () => listAiAgents(),
  })
}
