import { useMemo, useState } from "react"
import { useNavigate } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useMutation, type UseQueryResult } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Spinner } from "@/components/ui/spinner"
import { SeverityBadge, StatusBadge } from "@/components/status-badges"

import { triggerScaIssueAiReview, updateScaTaskIssueStatus } from "@/services/sca-service"
import { addItemsToTicketDraft } from "@/services/ticket-draft-service"
import type { FindingListFilters, FindingStatus, PageResponse, ScaIssueSummary } from "@/types/api"

const SCA_ISSUE_STATUS_OPTIONS: FindingStatus[] = ["OPEN", "CONFIRMED", "FALSE_POSITIVE", "RESOLVED", "WONT_FIX"]

export function ScaIssuesTable({
  taskId,
  query,
  page,
  onPageChange,
}: {
  taskId: string
  query: UseQueryResult<PageResponse<ScaIssueSummary>>
  page: Pick<FindingListFilters, "limit" | "offset">
  onPageChange: (next: Pick<FindingListFilters, "limit" | "offset">) => void
}) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [selectedIssueIds, setSelectedIssueIds] = useState<Set<string>>(new Set())
  const [batchError, setBatchError] = useState<string | null>(null)
  const [batchStatus, setBatchStatus] = useState<FindingStatus>("CONFIRMED")

  const items = query.data?.items ?? []
  const visibleIds = useMemo(() => items.map((it) => it.issueId), [items])
  const selectedCount = selectedIssueIds.size
  const allVisibleSelected = visibleIds.length > 0 && visibleIds.every((id) => selectedIssueIds.has(id))
  const anyVisibleSelected = visibleIds.some((id) => selectedIssueIds.has(id))

  const updateStatusMutation = useMutation({
    mutationFn: ({ issueId, status }: { issueId: string; status: FindingStatus }) =>
      updateScaTaskIssueStatus(taskId, issueId, { status }),
    onSuccess: () => {
      void query.refetch()
    },
  })

  const aiReviewMutation = useMutation({
    mutationFn: (issueId: string) => triggerScaIssueAiReview(issueId),
    onSuccess: () => {
      void query.refetch()
    },
    onError: (err: unknown) => {
      setBatchError(err instanceof Error ? err.message : "unknown")
      void query.refetch()
    },
  })

  const applySelectedMutation = useMutation({
    mutationFn: async () => {
      const ids = Array.from(selectedIssueIds)
      if (ids.length === 0) return
      for (const issueId of ids) {
        await updateScaTaskIssueStatus(taskId, issueId, { status: batchStatus })
      }
    },
    onMutate: () => setBatchError(null),
    onSuccess: () => {
      setSelectedIssueIds(new Set())
      void query.refetch()
    },
    onError: (err: unknown) => {
      setBatchError(err instanceof Error ? err.message : "unknown")
      void query.refetch()
    },
  })

  const stashSelectedMutation = useMutation({
    mutationFn: async () => {
      const ids = Array.from(selectedIssueIds)
      if (ids.length === 0) return
      await addItemsToTicketDraft({ items: ids.map((id) => ({ type: "SCA_ISSUE" as const, id })) })
    },
    onMutate: () => setBatchError(null),
    onSuccess: () => {
      setSelectedIssueIds(new Set())
    },
    onError: (err: unknown) => {
      setBatchError(err instanceof Error ? err.message : "unknown")
    },
  })

  const aiReviewSelectedMutation = useMutation({
    mutationFn: async () => {
      const ids = Array.from(selectedIssueIds)
      if (ids.length === 0) return
      for (const issueId of ids) {
        await triggerScaIssueAiReview(issueId)
      }
    },
    onMutate: () => setBatchError(null),
    onSuccess: () => {
      setSelectedIssueIds(new Set())
      void query.refetch()
    },
    onError: (err: unknown) => {
      setBatchError(err instanceof Error ? err.message : "unknown")
      void query.refetch()
    },
  })

  const toggleAllVisible = (checked: boolean) => {
    setSelectedIssueIds((prev) => {
      const next = new Set(prev)
      if (checked) {
        visibleIds.forEach((id) => next.add(id))
      } else {
        visibleIds.forEach((id) => next.delete(id))
      }
      return next
    })
  }

  const toggleOne = (issueId: string, checked: boolean) => {
    setSelectedIssueIds((prev) => {
      const next = new Set(prev)
      if (checked) next.add(issueId)
      else next.delete(issueId)
      return next
    })
  }

  if (query.isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Spinner size={18} /> {t("common.loading")}
      </div>
    )
  }
  if (query.isError) {
    return <p className="text-sm text-destructive">{t("sca.issues.error")}</p>
  }
  if (items.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("sca.issues.empty")}</p>
  }

  const total = query.data?.total ?? 0
  const limit = page.limit ?? 10
  const offset = page.offset ?? 0
  const pageIndex = total === 0 ? 0 : Math.floor(offset / Math.max(1, limit))
  const pageCount = Math.max(1, Math.ceil(Math.max(1, total) / Math.max(1, limit)))
  const canPrev = offset > 0
  const canNext = offset + limit < total
  const showBatch = selectedCount > 0

  return (
    <div className="space-y-4">
      {showBatch ? (
        <div className="flex flex-col gap-2 rounded-md border p-3 text-sm md:flex-row md:items-center md:justify-between">
          <div className="text-muted-foreground">{t("sca.issues.batch.selected", { count: selectedCount })}</div>
          <div className="flex flex-col gap-2 md:flex-row md:items-center">
            <select
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={batchStatus}
              disabled={applySelectedMutation.isPending}
              onChange={(event) => setBatchStatus(event.target.value as FindingStatus)}
            >
              {SCA_ISSUE_STATUS_OPTIONS.map((value) => (
                <option key={value} value={value}>
                  {t(`statuses.${value}`, { defaultValue: value })}
                </option>
              ))}
            </select>
            <Button
              type="button"
              size="sm"
              disabled={applySelectedMutation.isPending}
              onClick={() => applySelectedMutation.mutate()}
            >
              {applySelectedMutation.isPending ? t("common.loading") : t("sca.issues.batch.apply", { defaultValue: "Apply status" })}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={aiReviewSelectedMutation.isPending}
              onClick={() => aiReviewSelectedMutation.mutate()}
            >
              {aiReviewSelectedMutation.isPending ? t("common.loading") : t("sca.issues.batch.aiReview")}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={stashSelectedMutation.isPending}
              onClick={() => stashSelectedMutation.mutate()}
            >
              {stashSelectedMutation.isPending ? t("common.loading") : t("sca.issues.batch.stash")}
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setSelectedIssueIds(new Set())}>
              {t("sca.issues.batch.clear")}
            </Button>
          </div>
        </div>
      ) : null}

      {batchError ? <p className="text-sm text-destructive">{batchError}</p> : null}

      <div className="overflow-x-auto">
        <table className="min-w-full table-fixed text-sm">
          <colgroup>
            <col className="w-[44px]" />
            <col className="w-[120px]" />
            <col className="w-[260px]" />
            <col className="w-[260px]" />
            <col className="w-[120px]" />
            <col className="w-[140px]" />
            <col className="w-[260px]" />
          </colgroup>
          <thead className="border-b text-muted-foreground">
            <tr>
              <th className="py-2 text-left">
                <Checkbox
                  checked={allVisibleSelected}
                  indeterminate={anyVisibleSelected && !allVisibleSelected}
                  onChange={(event) => toggleAllVisible(event.target.checked)}
                  aria-label={t("common.selectAll", { defaultValue: "Select all" })}
                />
              </th>
              <th className="py-2 text-left">{t("sca.issues.columns.severity")}</th>
              <th className="py-2 text-left">{t("sca.issues.columns.vuln")}</th>
              <th className="py-2 text-left">{t("sca.issues.columns.package")}</th>
              <th className="py-2 text-left">{t("sca.issues.columns.status")}</th>
              <th className="py-2 text-left">{t("sca.issues.columns.review")}</th>
              <th className="py-2 text-left">{t("common.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((issue) => {
              const pkg = issue.packageName?.trim() || issue.componentName?.trim() || "—"
              const installedVersion = issue.installedVersion?.trim() || issue.componentVersion?.trim() || ""
              const fixedVersion = issue.fixedVersion?.trim() || ""
              const isUpdating = updateStatusMutation.isPending && updateStatusMutation.variables?.issueId === issue.issueId
              const isReviewing = aiReviewMutation.isPending && aiReviewMutation.variables === issue.issueId

              const reviewType = issue.review?.reviewType
              const reviewApplied = Boolean(issue.review?.appliedAt)
              const reviewLabel =
                reviewType === "AI"
                  ? reviewApplied
                    ? t("sca.issues.review.ai", { defaultValue: "AI审核" })
                    : t("sca.issues.review.aiPending", { defaultValue: "AI审核中" })
                  : reviewType === "HUMAN"
                    ? t("sca.issues.review.human", { defaultValue: "人工确认" })
                    : null
              const reviewTitle = issue.review
                ? [
                    `${t("sca.issues.review.type", { defaultValue: "类型" })}: ${issue.review.reviewType}`,
                    `${t("sca.issues.review.reviewer", { defaultValue: "审核人" })}: ${issue.review.reviewer}`,
                    `${t("sca.issues.review.verdict", { defaultValue: "结论" })}: ${issue.review.verdict}`,
                    issue.review.confidence != null ? `confidence: ${issue.review.confidence}` : null,
                    issue.review.appliedAt ? `appliedAt: ${issue.review.appliedAt}` : null,
                  ]
                    .filter(Boolean)
                    .join("\n")
                : undefined

              return (
                <tr key={issue.issueId} className="border-b last:border-b-0">
                  <td className="py-3 align-top">
                    <Checkbox
                      checked={selectedIssueIds.has(issue.issueId)}
                      onChange={(event) => toggleOne(issue.issueId, event.target.checked)}
                      aria-label={t("common.select", { defaultValue: "Select" })}
                    />
                  </td>
                  <td className="py-3 align-top">
                    <SeverityBadge severity={issue.severity} />
                  </td>
                  <td className="py-3 align-top">
                    <button
                      type="button"
                      className="block max-w-[240px] truncate text-left text-primary underline-offset-2 hover:underline"
                      title={issue.vulnId || issue.issueId}
                      onClick={() => navigate(`/sca/issues/${issue.issueId}`)}
                    >
                      {issue.vulnId || issue.issueId}
                    </button>
                    {issue.primaryUrl ? (
                      <a
                        className="mt-1 block max-w-[240px] truncate text-xs text-muted-foreground underline-offset-2 hover:underline"
                        href={issue.primaryUrl}
                        target="_blank"
                        rel="noreferrer"
                      >
                        {issue.primaryUrl}
                      </a>
                    ) : null}
                  </td>
                  <td className="py-3 align-top">
                    <span className="block max-w-[240px] truncate" title={pkg}>
                      {pkg}
                    </span>
                    {installedVersion ? <div className="mt-1 break-all text-xs text-muted-foreground">@{installedVersion}</div> : null}
                    {fixedVersion ? <div className="mt-1 break-all text-xs text-muted-foreground">→ {fixedVersion}</div> : null}
                  </td>
                  <td className="py-3 align-top">
                    <StatusBadge status={issue.status} />
                  </td>
                  <td className="py-3 align-top">
                    {reviewLabel ? (
                      <span
                        className={`inline-flex items-center rounded-full border px-2 py-1 text-xs ${
                          reviewType === "HUMAN" ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-sky-200 bg-sky-50 text-sky-700"
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
                    <div className="flex items-center gap-2">
                      <select
                        className="h-9 flex-1 rounded-md border border-input bg-background px-2 text-xs"
                        value={issue.status}
                        disabled={isUpdating}
                        onChange={(event) =>
                          updateStatusMutation.mutate({
                            issueId: issue.issueId,
                            status: event.target.value as FindingStatus,
                          })
                        }
                      >
                        {SCA_ISSUE_STATUS_OPTIONS.map((value) => (
                          <option key={value} value={value}>
                            {t(`statuses.${value}`, { defaultValue: value })}
                          </option>
                        ))}
                      </select>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="whitespace-nowrap"
                        disabled={isReviewing}
                        onClick={() => aiReviewMutation.mutate(issue.issueId)}
                      >
                        {isReviewing ? t("common.loading") : t("sca.issues.review.trigger")}
                      </Button>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>{t("common.pageStatus", { page: total === 0 ? 0 : pageIndex + 1, total: pageCount })}</span>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={!canPrev || query.isFetching}
            onClick={() => onPageChange({ limit, offset: Math.max(0, offset - limit) })}
          >
            {t("common.previous")}
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={!canNext || query.isFetching}
            onClick={() => onPageChange({ limit, offset: Math.min(total, offset + limit) })}
          >
            {t("common.next")}
          </Button>
        </div>
      </div>
    </div>
  )
}
