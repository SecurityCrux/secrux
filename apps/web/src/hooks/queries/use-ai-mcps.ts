import { useQuery } from "@tanstack/react-query"
import { listAiMcps } from "@/services/ai-integration-service"
import type { AiMcpConfig } from "@/types/api"

export function useAiMcpsQuery() {
  return useQuery<AiMcpConfig[]>({
    queryKey: ["ai", "mcps"],
    queryFn: () => listAiMcps(),
  })
}
