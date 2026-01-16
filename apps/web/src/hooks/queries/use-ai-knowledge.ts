import { useQuery } from "@tanstack/react-query"
import { listAiKnowledgeEntries } from "@/services/ai-integration-service"
import type { AiKnowledgeEntry } from "@/types/api"

export function useAiKnowledgeEntriesQuery() {
  return useQuery<AiKnowledgeEntry[]>({
    queryKey: ["ai", "knowledge"],
    queryFn: () => listAiKnowledgeEntries(),
  })
}
