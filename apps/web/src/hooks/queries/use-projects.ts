import { useQuery } from "@tanstack/react-query"

import { listProjects } from "@/services/project-service"

export function useProjectsQuery() {
  return useQuery({
    queryKey: ["projects"],
    queryFn: () => listProjects(),
  })
}

