import { useQuery } from "@tanstack/react-query"

import { listExecutors } from "@/services/executor-service"
import type { ExecutorStatus } from "@/types/api"

export function useExecutorsQuery(filters: { status?: ExecutorStatus; search?: string } = {}) {
  return useQuery({
    queryKey: ["executors", filters],
    queryFn: () => listExecutors(filters),
  })
}

