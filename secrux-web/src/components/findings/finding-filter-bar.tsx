import { useTranslation } from "react-i18next"

import { Input } from "@/components/ui/input"
import { FINDING_SEVERITY_OPTIONS, FINDING_STATUS_OPTIONS, type FindingLocalFilters } from "@/components/findings/finding-filters"

type Props = {
  filters: FindingLocalFilters
  onChange: (filters: FindingLocalFilters) => void
}

export function FindingFilterBar({ filters, onChange }: Props) {
  const { t } = useTranslation()
  return (
    <div className="grid gap-3 md:grid-cols-[2fr,1fr,1fr]">
      <Input
        placeholder={t("findings.filters.searchPlaceholder")}
        value={filters.search}
        onChange={(event) => onChange({ ...filters, search: event.target.value })}
      />
      <select
        className="h-9 rounded-md border border-input bg-background px-2 text-sm"
        value={filters.severity}
        onChange={(event) => onChange({ ...filters, severity: event.target.value as FindingLocalFilters["severity"] })}
      >
        <option value="ALL">{t("common.all")}</option>
        {FINDING_SEVERITY_OPTIONS.map((value) => (
          <option key={value} value={value}>
            {t(`severities.${value}`, { defaultValue: value })}
          </option>
        ))}
      </select>
      <select
        className="h-9 rounded-md border border-input bg-background px-2 text-sm"
        value={filters.status}
        onChange={(event) => onChange({ ...filters, status: event.target.value as FindingLocalFilters["status"] })}
      >
        <option value="ALL">{t("common.all")}</option>
        {FINDING_STATUS_OPTIONS.map((value) => (
          <option key={value} value={value}>
            {t(`statuses.${value}`, { defaultValue: value })}
          </option>
        ))}
      </select>
    </div>
  )
}

