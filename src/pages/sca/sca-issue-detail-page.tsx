import { useEffect, useMemo, useRef, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Spinner } from "@/components/ui/spinner"
import { SeverityBadge, StatusBadge } from "@/components/status-badges"
import { MarkdownView } from "@/components/markdown/markdown-view"
import { CvssRing } from "@/components/sca/cvss-ring"
import { ScaUsageEvidenceCard } from "@/components/sca/sca-usage-evidence-card"

import { addItemsToTicketDraft } from "@/services/ticket-draft-service"
import { getAiJob } from "@/services/ai-job-service"
import { getScaIssueDetail, getScaTaskIssues, triggerScaIssueAiReview, updateScaIssueStatus } from "@/services/sca-service"
import type { AiJobTicket, FindingListFilters, FindingStatus, PageResponse, ScaIssueDetail, ScaIssueSummary } from "@/types/api"

const ISSUE_STATUS_OPTIONS: FindingStatus[] = ["OPEN", "CONFIRMED", "FALSE_POSITIVE", "RESOLVED", "WONT_FIX"]

export function ScaIssueDetailPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { issueId } = useParams<{ issueId: string }>()
  const [stashError, setStashError] = useState<string | null>(null)
  const [relatedPage, setRelatedPage] = useState<Pick<FindingListFilters, "limit" | "offset">>({ limit: 10, offset: 0 })
  const [aiJobId, setAiJobId] = useState<string | null>(null)
  const [aiSubmitError, setAiSubmitError] = useState<string | null>(null)
  const [refsExpanded, setRefsExpanded] = useState(false)
  const lastTaskIdRef = useRef<string | null>(null)

  const detailQuery = useQuery<ScaIssueDetail | undefined>({
    queryKey: ["sca-issue", issueId],
    queryFn: () => getScaIssueDetail(issueId!),
    enabled: Boolean(issueId),
  })

  const detail = detailQuery.data

  useEffect(() => {
    if (!detail?.taskId) return
    if (lastTaskIdRef.current !== detail.taskId) {
      lastTaskIdRef.current = detail.taskId
      setRelatedPage((prev) => ({ ...prev, offset: 0 }))
    }
  }, [detail?.taskId])

  useEffect(() => {
    setStashError(null)
    setAiJobId(null)
    setAiSubmitError(null)
    setRefsExpanded(false)
  }, [issueId])

  const relatedQuery = useQuery<PageResponse<ScaIssueSummary> | undefined>({
    queryKey: ["sca", detail?.taskId, "issues", relatedPage],
    queryFn: () => getScaTaskIssues(detail!.taskId, relatedPage),
    enabled: Boolean(detail?.taskId),
  })

  const statusMutation = useMutation({
    mutationFn: (status: FindingStatus) => updateScaIssueStatus(issueId!, { status }),
    onMutate: async (status) => {
      await queryClient.cancelQueries({ queryKey: ["sca-issue", issueId] })
      const prev = queryClient.getQueryData<ScaIssueDetail | undefined>(["sca-issue", issueId])
      if (prev) {
        queryClient.setQueryData(["sca-issue", issueId], { ...prev, status })
      }
      return { prev }
    },
    onError: (_err, _status, ctx) => {
      if (ctx?.prev) {
        queryClient.setQueryData(["sca-issue", issueId], ctx.prev)
      }
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: ["sca-issue", issueId] })
      if (detail?.taskId) {
        await queryClient.invalidateQueries({ queryKey: ["sca", detail.taskId, "issues"] })
      }
    },
  })

  const aiReviewMutation = useMutation<AiJobTicket, unknown, void>({
    mutationFn: () => triggerScaIssueAiReview(issueId!),
    onMutate: () => {
      setAiSubmitError(null)
      setAiJobId(null)
    },
    onSuccess: (ticket) => {
      setAiJobId(ticket.jobId)
      void queryClient.invalidateQueries({ queryKey: ["sca-issue", issueId] })
      if (detail?.taskId) {
        void queryClient.invalidateQueries({ queryKey: ["sca", detail.taskId, "issues"] })
      }
    },
    onError: (err) => {
      setAiSubmitError(err instanceof Error ? err.message : "unknown")
    },
  })

  const aiJobQuery = useQuery<AiJobTicket>({
    queryKey: ["ai-job", aiJobId],
    queryFn: () => getAiJob(aiJobId!),
    enabled: Boolean(aiJobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "QUEUED" || status === "RUNNING" ? 1500 : false
    },
  })

  useEffect(() => {
    if (aiJobQuery.data?.status === "COMPLETED" || aiJobQuery.data?.status === "FAILED") {
      void queryClient.invalidateQueries({ queryKey: ["sca-issue", issueId] })
      if (detail?.taskId) {
        void queryClient.invalidateQueries({ queryKey: ["sca", detail.taskId, "issues"] })
      }
    }
  }, [aiJobQuery.data?.status, detail?.taskId, issueId, queryClient])

  const stashMutation = useMutation({
    mutationFn: () => addItemsToTicketDraft({ items: [{ type: "SCA_ISSUE", id: issueId! }] }),
    onMutate: () => setStashError(null),
    onError: (err: unknown) => {
      setStashError(err instanceof Error ? err.message : "unknown")
    },
  })

  const review = detail?.review ?? null
  const uiLang: "zh" | "en" = i18n.language?.startsWith("zh") ? "zh" : "en"
  const opinionText =
    review?.opinionI18n?.[uiLang] ?? review?.opinionI18n?.en ?? review?.opinionI18n?.zh ?? null
  const aiSummary = opinionText?.summary ?? null
  const aiFixHint = opinionText?.fixHint ?? null

  const vulnTitle = detail?.title?.trim() || null
  const vulnDescription = detail?.description || null
  const referenceLinks = useMemo(() => (detail?.references ?? []).filter((ref) => Boolean(ref && ref.trim())), [detail?.references])
  const displayedReferences = refsExpanded ? referenceLinks : referenceLinks.slice(0, 2)
  const remainingRefCount = Math.max(0, referenceLinks.length - displayedReferences.length)
  const cvss = detail?.cvss ?? null

  if (detailQuery.isLoading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center text-muted-foreground">
        <Spinner size={20} />
      </div>
    )
  }

  if (detailQuery.isError || !detail) {
    return (
      <div className="space-y-3">
        <Button variant="ghost" onClick={() => navigate(-1)}>
          {t("common.back")}
        </Button>
        <p className="text-sm text-destructive">{t("sca.issues.detailError", { defaultValue: "Failed to load issue detail." })}</p>
      </div>
    )
  }

  const pkgName = detail.packageName?.trim() || detail.componentName?.trim() || "—"
  const installedVersion = detail.installedVersion?.trim() || detail.componentVersion?.trim() || ""
  const fixedVersion = detail.fixedVersion?.trim() || ""

  const relatedItems = relatedQuery.data?.items ?? []
  const relatedTotal = relatedQuery.data?.total ?? 0
  const relatedLimit = relatedQuery.data?.limit ?? relatedPage.limit ?? 10
  const relatedOffset = relatedQuery.data?.offset ?? relatedPage.offset ?? 0
  const relatedPageIndex = relatedTotal === 0 ? 0 : Math.floor(relatedOffset / Math.max(1, relatedLimit))
  const relatedPageCount = Math.max(1, Math.ceil(Math.max(1, relatedTotal) / Math.max(1, relatedLimit)))
  const canPrev = relatedOffset > 0
  const canNext = relatedOffset + relatedLimit < relatedTotal

  return (
    <div className="min-w-0 space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
              {t("common.back")}
            </Button>
            <SeverityBadge severity={detail.severity} />
            <StatusBadge status={detail.status} />
            <Badge variant="outline">{t("sca.issues.detail.badge", { defaultValue: "SCA" })}</Badge>
          </div>
          <h1 className="mt-2 break-all text-2xl font-semibold">
            {detail.vulnId} · {detail.issueId}
          </h1>
          <p className="break-all text-sm text-muted-foreground">
            {pkgName}
            {installedVersion ? `@${installedVersion}` : ""}
            {fixedVersion ? ` → ${fixedVersion}` : ""}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <select
            className="h-10 rounded-md border border-input bg-background px-3 text-sm"
            value={detail.status}
            onChange={(event) => statusMutation.mutate(event.target.value as FindingStatus)}
            disabled={statusMutation.isPending}
          >
            {ISSUE_STATUS_OPTIONS.map((status) => (
              <option key={status} value={status}>
                {t(`statuses.${status}`, { defaultValue: status })}
              </option>
            ))}
          </select>
          <Button
            size="sm"
            variant="outline"
            disabled={stashMutation.isPending}
            onClick={() => stashMutation.mutate()}
          >
            {stashMutation.isPending ? t("common.loading") : t("tickets.draft.addSelected", { defaultValue: "Stash to ticket" })}
          </Button>
        </div>
      </div>

      {stashError ? <p className="text-sm text-destructive">{stashError}</p> : null}

      <div className="grid gap-4 lg:grid-cols-[320px,1fr]">
        <Card className="h-full">
          <CardHeader>
            <CardTitle className="text-base">{t("sca.issues.related", { defaultValue: "Other issues in this task" })}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {relatedQuery.isLoading ? (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Spinner size={16} />
                {t("common.loading")}
              </div>
            ) : relatedItems.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("sca.issues.empty")}</p>
            ) : (
              <div className="space-y-2">
                {relatedItems.map((item) => {
                  const pkg = item.packageName?.trim() || item.componentName?.trim() || "—"
                  return (
                    <button
                      key={item.issueId}
                      type="button"
                      onClick={() => navigate(`/sca/issues/${item.issueId}`)}
                      className={`w-full rounded-md border px-3 py-2 text-left text-sm transition hover:border-primary ${
                        item.issueId === detail.issueId ? "border-primary bg-primary/5" : "border-border bg-background"
                      }`}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <span className="block max-w-[200px] truncate font-medium">{item.vulnId || item.issueId}</span>
                        <span className="truncate text-xs text-muted-foreground">{item.issueId}</span>
                      </div>
                      <div className="mt-1 max-w-[240px] truncate text-xs text-muted-foreground">{pkg}</div>
                      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                        <StatusBadge status={item.status} />
                        <SeverityBadge severity={item.severity} />
                        {item.sourceEngine ? <span className="truncate text-muted-foreground">{item.sourceEngine}</span> : null}
                      </div>
                    </button>
                  )
                })}
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>
                    {t("common.pageStatus", {
                      page: relatedTotal === 0 ? 0 : relatedPageIndex + 1,
                      total: relatedPageCount,
                    })}
                  </span>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!canPrev || relatedQuery.isFetching}
                      onClick={() => setRelatedPage((prev) => ({ ...prev, offset: Math.max(0, relatedOffset - relatedLimit) }))}
                    >
                      {t("common.previous")}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!canNext || relatedQuery.isFetching}
                      onClick={() => setRelatedPage((prev) => ({ ...prev, offset: Math.min(relatedTotal, relatedOffset + relatedLimit) }))}
                    >
                      {t("common.next")}
                    </Button>
                  </div>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        <div className="space-y-4">
          <ScaUsageEvidenceCard issueId={detail.issueId} />

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("sca.issues.detail.info", { defaultValue: "Details" })}</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 md:grid-cols-2">
              <div className="space-y-3 text-sm">
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">{t("sca.issues.detail.task", { defaultValue: "Task" })}</div>
                  <div className="break-all text-xs">{detail.taskName ?? "—"}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">{t("sca.issues.detail.project", { defaultValue: "Project" })}</div>
                  <div className="break-all text-xs">{detail.projectName ?? "—"}</div>
                </div>
                {detail.componentPurl ? (
                  <div className="space-y-1">
                    <div className="text-xs font-medium text-muted-foreground">{t("sca.issues.detail.purl", { defaultValue: "PURL" })}</div>
                    <div className="break-all font-mono text-xs">{detail.componentPurl}</div>
                  </div>
                ) : null}
                {detail.primaryUrl ? (
                  <div className="space-y-1">
                    <div className="text-xs font-medium text-muted-foreground">{t("sca.issues.detail.primaryUrl", { defaultValue: "Reference" })}</div>
                    <a className="break-all text-xs underline-offset-2 hover:underline" href={detail.primaryUrl} target="_blank" rel="noreferrer">
                      {detail.primaryUrl}
                    </a>
                  </div>
                ) : null}
                {detail.sourceEngine ? (
                  <div className="space-y-1">
                    <div className="text-xs font-medium text-muted-foreground">{t("sca.issues.detail.introducedBy", { defaultValue: "Introduced by" })}</div>
                    <div className="break-all font-mono text-xs">{detail.sourceEngine}</div>
                  </div>
                ) : null}
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">{t("sca.issues.detail.createdAt", { defaultValue: "Created" })}</div>
                  <div className="text-xs text-muted-foreground">{new Date(detail.createdAt).toLocaleString()}</div>
                </div>
                {detail.updatedAt ? (
                  <div className="space-y-1">
                    <div className="text-xs font-medium text-muted-foreground">{t("common.updatedAt", { defaultValue: "Updated at" })}</div>
                    <div className="text-xs text-muted-foreground">{new Date(detail.updatedAt).toLocaleString()}</div>
                  </div>
                ) : null}
              </div>
              <div className="flex items-start justify-start md:justify-end">
                {cvss ? (
                  <CvssRing score={cvss.score} vector={cvss.vector} source={cvss.source} className="flex-col items-start gap-2" />
                ) : (
                  <p className="text-sm text-muted-foreground">{t("common.notAvailable")}</p>
                )}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex flex-wrap items-center justify-between gap-2 text-base">
                <span>{t("sca.issues.review.title", { defaultValue: "AI Review" })}</span>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={aiReviewMutation.isPending || aiJobQuery.data?.status === "QUEUED" || aiJobQuery.data?.status === "RUNNING"}
                  onClick={() => aiReviewMutation.mutate()}
                >
                  {aiReviewMutation.isPending ? t("common.loading") : t("sca.issues.review.trigger", { defaultValue: "AI Review" })}
                </Button>
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              {aiSubmitError ? <p className="text-sm text-destructive">{aiSubmitError}</p> : null}
              {aiJobQuery.data ? (
                <div className="text-xs text-muted-foreground">
                  {t("sca.issues.review.jobStatus", { defaultValue: "Job" })}: {aiJobQuery.data.status}
                </div>
              ) : null}
              {review?.reviewType ? (
                <div className="space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary">{review.reviewType}</Badge>
                    <span className="text-muted-foreground">
                      {t("sca.issues.review.verdict", { defaultValue: "Verdict" })}: {review.verdict}
                    </span>
                    {typeof review.confidence === "number" ? (
                      <span className="text-muted-foreground">
                        · {t("sca.issues.review.confidence", { defaultValue: "Confidence" })}: {review.confidence.toFixed(2)}
                      </span>
                    ) : null}
                  </div>
                  <div>
                    <div className="text-muted-foreground">{t("sca.issues.review.summary", { defaultValue: "Summary" })}</div>
                    <div className="mt-1 whitespace-pre-wrap break-words">{aiSummary ?? "—"}</div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">{t("sca.issues.review.fixHint", { defaultValue: "Fix hint" })}</div>
                    <div className="mt-1 whitespace-pre-wrap break-words">{aiFixHint ?? "—"}</div>
                  </div>
                </div>
              ) : (
                <p className="text-sm text-muted-foreground">{t("sca.issues.review.empty", { defaultValue: "No review yet." })}</p>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("sca.issues.detail.summary", { defaultValue: "Summary" })}</CardTitle>
            </CardHeader>
            <CardContent className="min-w-0 space-y-2">
              <p className="break-words text-sm">{vulnTitle || detail.vulnId}</p>
              <p className="break-words text-xs text-muted-foreground">{pkgName}</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("sca.issues.detail.description", { defaultValue: "Description" })}</CardTitle>
            </CardHeader>
            <CardContent className="min-w-0">
              <MarkdownView content={vulnDescription || null} />
            </CardContent>
          </Card>

          {referenceLinks.length > 0 ? (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">{t("sca.issues.detail.references", { defaultValue: "References" })}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                {displayedReferences.map((ref) => (
                  <a
                    key={ref}
                    className="block break-all text-xs underline-offset-2 hover:underline"
                    href={ref}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {ref}
                  </a>
                ))}
                {remainingRefCount > 0 ? (
                  <Button variant="ghost" size="sm" className="px-0 text-xs" onClick={() => setRefsExpanded((prev) => !prev)}>
                    {refsExpanded ? t("common.showLess", { defaultValue: "Show less" }) : t("common.showMore", { defaultValue: "Show more" })}
                  </Button>
                ) : null}
              </CardContent>
            </Card>
          ) : null}
        </div>
      </div>
    </div>
  )
}
