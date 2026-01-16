import { useQuery } from "@tanstack/react-query"
import { listTasks } from "@/services/task-service"
import type { PageResponse, TaskListFilters, TaskSummary } from "@/types/api"

type UseTasksQueryOptions = {
  enabled?: boolean
  refetchInterval?: number | false
}

export function useTasksQuery(filters: TaskListFilters, options: UseTasksQueryOptions = {}) {
  return useQuery<PageResponse<TaskSummary>>({
    queryKey: ["tasks", filters],
    queryFn: () => listTasks(filters),
    enabled: options.enabled ?? true,
    staleTime: 30_000,
    refetchInterval: (options.enabled ?? true) ? (options.refetchInterval ?? 10_000) : false,
  })
}
