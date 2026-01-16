import { useQuery } from "@tanstack/react-query"

import { listRules } from "@/services/rule-service"

export function useRulesQuery() {
  return useQuery({
    queryKey: ["rules"],
    queryFn: () => listRules(),
  })
}

