import { useTranslation } from "react-i18next"

import { FINDING_STATUS_OPTIONS } from "@/components/findings/finding-filters"
import type { FindingSummary } from "@/types/api"

export function FindingSummaryChips({ findings }: { findings: FindingSummary[] }) {
  const { t } = useTranslation()
  const counts = findings.reduce<Record<string, number>>((acc, finding) => {
    acc[finding.status] = (acc[finding.status] ?? 0) + 1
    return acc
  }, {})

  return (
    <div className="flex flex-wrap gap-2 text-xs">
      {FINDING_STATUS_OPTIONS.map((status) => (
        <div key={status} className="rounded-full border px-3 py-1 text-muted-foreground">
          {t(`statuses.${status}`, { defaultValue: status })}: {counts[status] ?? 0}
        </div>
      ))}
    </div>
  )
}

