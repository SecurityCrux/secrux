import { useTranslation } from "react-i18next"

import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { FINDING_STATUS_OPTIONS } from "@/components/findings/finding-filters"
import type { FindingStatus } from "@/types/api"

type Props = {
  selectedCount: number
  status: FindingStatus
  reason: string
  onStatusChange: (status: FindingStatus) => void
  onReasonChange: (reason: string) => void
  onStash: () => void
  onApply: () => void
  stashPending: boolean
  applyPending: boolean
}

export function FindingBatchBar({
  selectedCount,
  status,
  reason,
  onStatusChange,
  onReasonChange,
  onStash,
  onApply,
  stashPending,
  applyPending,
}: Props) {
  const { t } = useTranslation()
  return (
    <div className="flex flex-col gap-2 rounded-md border p-3 text-sm md:flex-row md:items-center md:justify-between">
      <div className="text-muted-foreground">
        {t("findings.batch.selected", { defaultValue: "已选择 {{count}} 个", count: selectedCount })}
      </div>
      <div className="flex flex-col gap-2 md:flex-row md:items-center">
        <select
          className="h-9 rounded-md border border-input bg-background px-2 text-sm"
          value={status}
          onChange={(event) => onStatusChange(event.target.value as FindingStatus)}
        >
          {FINDING_STATUS_OPTIONS.map((value) => (
            <option key={value} value={value}>
              {t(`statuses.${value}`, { defaultValue: value })}
            </option>
          ))}
        </select>
        <Input
          className="md:w-[260px]"
          placeholder={t("findings.batch.reason", { defaultValue: "原因（可选）" })}
          value={reason}
          onChange={(event) => onReasonChange(event.target.value)}
        />
        <Button type="button" size="sm" variant="outline" disabled={stashPending} onClick={onStash}>
          {stashPending ? t("common.loading") : t("tickets.draft.addSelected", { defaultValue: "暂存到工单" })}
        </Button>
        <Button type="button" size="sm" disabled={applyPending} onClick={onApply}>
          {applyPending
            ? t("findings.batch.applying", { defaultValue: "处理中..." })
            : t("findings.batch.apply", { defaultValue: "人工确认（批量）" })}
        </Button>
      </div>
    </div>
  )
}

