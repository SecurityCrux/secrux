import { useQuery } from "@tanstack/react-query"

import { listAiProviderTemplates } from "@/services/ai-client-service"
import type { AiProviderTemplate } from "@/types/api"

export function useAiProviderTemplatesQuery() {
  return useQuery<AiProviderTemplate[]>({
    queryKey: ["ai-provider-templates"],
    queryFn: () => listAiProviderTemplates(),
    staleTime: 1000 * 60 * 60 * 6, // 6 hours
  })
}
