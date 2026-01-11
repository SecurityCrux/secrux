import type { FindingListFilters, FindingStatus } from "@/types/api"

export const FINDING_SEVERITY_OPTIONS = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"] as const
export const FINDING_STATUS_OPTIONS: FindingStatus[] = ["OPEN", "CONFIRMED", "FALSE_POSITIVE", "RESOLVED", "WONT_FIX"]

export type FindingLocalFilters = {
  severity: (typeof FINDING_SEVERITY_OPTIONS)[number] | "ALL"
  status: FindingStatus | "ALL"
  search: string
}

export function toFindingListFilters(filters: FindingLocalFilters): FindingListFilters {
  return {
    severity: filters.severity === "ALL" ? undefined : filters.severity,
    status: filters.status === "ALL" ? undefined : filters.status,
    search: filters.search.trim() || undefined,
  }
}

