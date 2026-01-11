import { useTranslation } from "react-i18next"
import type { UseMutationResult } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { SeverityBadge, StatusBadge } from "@/components/status-badges"
import { FINDING_STATUS_OPTIONS } from "@/components/findings/finding-filters"
import type { AiJobTicket, FindingStatus, FindingSummary } from "@/types/api"

export type FindingTableProps = {
  findings: FindingSummary[]
  isLoading: boolean
  error: boolean
  total: number
  pageIndex: number
  pageCount: number
  canPrev: boolean
  canNext: boolean
  isFetching: boolean
  onOpenDetail: (findingId: string) => void
  onPageChange: (direction: "prev" | "next") => void
  updateStatusMutation: UseMutationResult<FindingSummary, unknown, { findingId: string; status: FindingStatus }>
  aiReviewMutation: UseMutationResult<AiJobTicket, unknown, string>
  selectedFindingIds: Set<string>
  onToggleFinding: (findingId: string) => void
  onToggleAll: (checked: boolean) => void
}

export function FindingTable({
  findings,
  isLoading,
  error,
  total,
  pageIndex,
  pageCount,
  canPrev,
  canNext,
  isFetching,
  onOpenDetail,
  onPageChange,
  updateStatusMutation,
  aiReviewMutation,
  selectedFindingIds,
  onToggleFinding,
  onToggleAll,
}: FindingTableProps) {
  const { t } = useTranslation()
  const allSelected = findings.length > 0 && findings.every((finding) => selectedFindingIds.has(finding.findingId))
  const anySelected = findings.some((finding) => selectedFindingIds.has(finding.findingId))

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Spinner size={18} /> {t("common.loading")}
      </div>
    )
  }

  if (error) {
    return <p className="text-sm text-destructive">{t("findings.error")}</p>
  }

  if (findings.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("findings.empty")}</p>
  }

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full table-fixed text-sm">
        <colgroup>
          <col className="w-[44px]" />
          <col className="w-[120px]" />
          <col className="w-[260px]" />
          <col className="w-[120px]" />
          <col className="w-[90px]" />
          <col className="w-[140px]" />
          <col className="w-[220px]" />
          <col className="w-[260px]" />
        </colgroup>
        <thead className="border-b text-muted-foreground">
          <tr>
            <th className="py-2 text-left">
              <Checkbox
                checked={allSelected}
                indeterminate={anySelected && !allSelected}
                onChange={(event) => onToggleAll(event.target.checked)}
                aria-label={t("findings.actions.selectAll", { defaultValue: "Select all" })}
              />
            </th>
            <th className="py-2 text-left">{t("findings.columns.severity")}</th>
            <th className="py-2 text-left">{t("findings.columns.rule")}</th>
            <th className="py-2 text-left">{t("findings.columns.status")}</th>
            <th className="py-2 text-left">{t("findings.columns.dataflow")}</th>
            <th className="py-2 text-left">{t("findings.columns.review", { defaultValue: "复核" })}</th>
            <th className="py-2 text-left">{t("findings.columns.introducedBy")}</th>
            <th className="py-2 text-left">{t("findings.columns.actions")}</th>
          </tr>
        </thead>
        <tbody>
          {findings.map((finding) => {
            const isUpdating = updateStatusMutation.isPending && updateStatusMutation.variables?.findingId === finding.findingId
            const isReviewing = aiReviewMutation.isPending && aiReviewMutation.variables === finding.findingId
            const isSelected = selectedFindingIds.has(finding.findingId)
            const reviewType = finding.review?.reviewType
            const reviewApplied = Boolean(finding.review?.appliedAt)
            const reviewLabel =
              reviewType === "AI"
                ? reviewApplied
                  ? t("findings.review.ai", { defaultValue: "AI审核" })
                  : t("findings.review.aiPending", { defaultValue: "AI审核中" })
                : reviewType === "HUMAN"
                  ? t("findings.review.human", { defaultValue: "人工确认" })
                  : null
            const reviewTitle = finding.review
              ? [
                  `${t("findings.review.type", { defaultValue: "类型" })}: ${finding.review.reviewType}`,
                  `${t("findings.review.reviewer", { defaultValue: "审核人" })}: ${finding.review.reviewer}`,
                  `${t("findings.review.verdict", { defaultValue: "结论" })}: ${finding.review.verdict}`,
                  finding.review.confidence != null ? `confidence: ${finding.review.confidence}` : null,
                  finding.review.appliedAt ? `appliedAt: ${finding.review.appliedAt}` : null,
                ]
                  .filter(Boolean)
                  .join("\n")
              : undefined
            return (
              <tr key={finding.findingId} className="border-b last:border-b-0">
                <td className="py-3 align-top">
                  <Checkbox
                    checked={isSelected}
                    onChange={() => onToggleFinding(finding.findingId)}
                    aria-label={t("findings.actions.select", { defaultValue: "Select" })}
                  />
                </td>
                <td className="py-3 align-top">
                  <SeverityBadge severity={finding.severity} />
                </td>
                <td className="py-3 align-top">
                  <button
                    type="button"
                    className="block max-w-[220px] truncate text-left text-primary underline-offset-2 hover:underline"
                    title={finding.ruleId ?? "—"}
                    onClick={() => onOpenDetail(finding.findingId)}
                  >
                    {finding.ruleId ?? t("findings.untitledRule", { defaultValue: "Untitled rule" })}
                  </button>
                </td>
                <td className="py-3 align-top">
                  <StatusBadge status={finding.status} />
                </td>
                <td className="py-3 align-top">
                  {finding.hasDataFlow ? (
                    <Badge variant="secondary" className="text-[10px]">
                      {t("common.yes")}
                    </Badge>
                  ) : (
                    <span className="text-xs text-muted-foreground">—</span>
                  )}
                </td>
                <td className="py-3 align-top">
                  {reviewLabel ? (
                    <span
                      className={`inline-flex items-center rounded-full border px-2 py-1 text-xs ${
                        reviewType === "HUMAN"
                          ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                          : "border-sky-200 bg-sky-50 text-sky-700"
                      }`}
                      title={reviewTitle}
                    >
                      {reviewLabel}
                    </span>
                  ) : (
                    <span className="text-xs text-muted-foreground">—</span>
                  )}
                </td>
                <td className="py-3 align-top">
                  <span className="block max-w-[200px] truncate" title={finding.sourceEngine ?? "—"}>
                    {finding.sourceEngine ?? "—"}
                  </span>
                </td>
                <td className="py-3 align-top">
                  <div className="flex items-center gap-2">
                    <select
                      className="h-9 flex-1 rounded-md border border-input bg-background px-2 text-xs"
                      value={finding.status}
                      disabled={isUpdating}
                      onChange={(event) =>
                        updateStatusMutation.mutate({
                          findingId: finding.findingId,
                          status: event.target.value as FindingStatus,
                        })
                      }
                    >
                      {FINDING_STATUS_OPTIONS.map((status) => (
                        <option key={status} value={status}>
                          {t(`statuses.${status}`, { defaultValue: status })}
                        </option>
                      ))}
                    </select>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="whitespace-nowrap"
                      disabled={isReviewing}
                      onClick={() => aiReviewMutation.mutate(finding.findingId)}
                    >
                      {isReviewing ? t("findings.actions.reviewing") : t("findings.actions.aiReview")}
                    </Button>
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <div className="mt-3 flex items-center justify-between text-xs text-muted-foreground">
        <span>
          {t("common.pageStatus", {
            page: total === 0 ? 0 : pageIndex + 1,
            total: pageCount,
          })}
        </span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => onPageChange("prev")} disabled={!canPrev || isFetching}>
            {t("common.previous")}
          </Button>
          <Button variant="outline" size="sm" onClick={() => onPageChange("next")} disabled={!canNext || isFetching}>
            {t("common.next")}
          </Button>
        </div>
      </div>
    </div>
  )
}

