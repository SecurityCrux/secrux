import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { useQuery } from "@tanstack/react-query"

import { CodeSnippetView } from "@/components/code-snippet/code-snippet-view"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Spinner } from "@/components/ui/spinner"

import { getScaIssueSnippet, getScaIssueUsage } from "@/services/sca-service"
import type { CodeSnippet, FindingListFilters, PageResponse, ScaUsageEntry } from "@/types/api"

export function ScaUsageEvidenceCard({ issueId }: { issueId: string }) {
  const { t } = useTranslation()
  const [page, setPage] = useState<Pick<FindingListFilters, "limit" | "offset">>({ limit: 20, offset: 0 })
  const [selectedId, setSelectedId] = useState<string | null>(null)

  useEffect(() => {
    setPage((prev) => ({ ...prev, offset: 0 }))
    setSelectedId(null)
  }, [issueId])

  const usageQuery = useQuery<PageResponse<ScaUsageEntry> | undefined>({
    queryKey: ["sca-issue", issueId, "usage", page],
    queryFn: () => getScaIssueUsage(issueId, page),
    enabled: Boolean(issueId),
  })

  const usageItems = usageQuery.data?.items ?? []
  const usageTotal = usageQuery.data?.total ?? 0
  const usageLimit = usageQuery.data?.limit ?? page.limit ?? 20
  const usageOffset = usageQuery.data?.offset ?? page.offset ?? 0
  const usagePageIndex = usageTotal === 0 ? 0 : Math.floor(usageOffset / Math.max(1, usageLimit))
  const usagePageCount = Math.max(1, Math.ceil(Math.max(1, usageTotal) / Math.max(1, usageLimit)))
  const canPrev = usageOffset > 0
  const canNext = usageOffset + usageLimit < usageTotal

  const selectedEntry = useMemo(() => {
    if (!selectedId) return null
    return usageItems.find((entry) => buildUsageEntryId(entry) === selectedId) ?? null
  }, [selectedId, usageItems])

  const snippetParams = useMemo(() => {
    if (!selectedEntry?.file) return null
    const lineRaw = selectedEntry.startLine ?? selectedEntry.line
    if (lineRaw == null) return null
    return {
      path: selectedEntry.file,
      line: lineRaw,
      context: 8,
      receiver: selectedEntry.receiver ?? undefined,
      symbol: selectedEntry.symbol ?? undefined,
      kind: selectedEntry.kind ?? undefined,
      language: selectedEntry.language ?? undefined,
    }
  }, [selectedEntry])

  const snippetQuery = useQuery<CodeSnippet | null>({
    queryKey: ["sca-issue", issueId, "usage-snippet", snippetParams],
    queryFn: () => getScaIssueSnippet(issueId, snippetParams!),
    enabled: Boolean(issueId && snippetParams?.path && snippetParams?.line),
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("sca.issues.usage.title", { defaultValue: "Usage evidence" })}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {usageQuery.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} />
            {t("common.loading")}
          </div>
        ) : usageQuery.isError ? (
          <p className="text-sm text-destructive">{t("sca.issues.usage.error", { defaultValue: "Usage evidence load failed." })}</p>
        ) : usageItems.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("sca.issues.usage.empty", { defaultValue: "No usage evidence." })}</p>
        ) : (
          <div className="grid gap-3 lg:grid-cols-[280px,1fr]">
            <div className="space-y-2">
              <div className="max-h-[360px] space-y-2 overflow-auto pr-1">
                {usageItems.map((entry, idx) => {
                  const entryId = buildUsageEntryId(entry)
                  const isSelected = entryId === selectedId
                  const pathText = entry.file ?? "—"
                  const lineText = entry.startLine ?? entry.line
                  const kind = entry.kind ?? undefined
                  const usageSignature =
                    entry.receiver && entry.callee
                      ? `${entry.receiver}.${entry.callee}()`
                      : entry.symbol
                        ? entry.symbol
                        : null
                  return (
                    <button
                      key={`${entryId}:${idx}`}
                      type="button"
                      onClick={() => setSelectedId(entryId)}
                      className={`w-full rounded-md border px-3 py-2 text-left text-sm transition hover:border-primary ${
                        isSelected ? "border-primary bg-primary/5" : "border-border bg-background"
                      }`}
                    >
                      <div className="break-all font-mono text-[11px] text-muted-foreground">
                        {pathText}
                        {lineText != null ? `:${lineText}` : ""}
                        {entry.language ? ` · ${entry.language}` : ""}
                        {kind ? ` · ${kind}` : ""}
                      </div>
                      {usageSignature ? (
                        <div className="mt-1 break-all font-mono text-[11px] text-muted-foreground">
                          {usageSignature}
                          {typeof entry.confidence === "number" ? ` · ${(entry.confidence * 100).toFixed(0)}%` : ""}
                        </div>
                      ) : null}
                      {entry.snippet ? (
                        <div className="mt-2 max-h-[72px] overflow-hidden whitespace-pre-wrap break-words text-xs text-muted-foreground">
                          {entry.snippet}
                        </div>
                      ) : null}
                    </button>
                  )
                })}
              </div>
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  {t("common.pageStatus", {
                    page: usageTotal === 0 ? 0 : usagePageIndex + 1,
                    total: usagePageCount,
                  })}
                </span>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!canPrev || usageQuery.isFetching}
                    onClick={() => setPage((prev) => ({ ...prev, offset: Math.max(0, usageOffset - usageLimit) }))}
                  >
                    {t("common.previous")}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!canNext || usageQuery.isFetching}
                    onClick={() => setPage((prev) => ({ ...prev, offset: Math.min(usageTotal, usageOffset + usageLimit) }))}
                  >
                    {t("common.next")}
                  </Button>
                </div>
              </div>
            </div>

            <div className="min-w-0">
              <div className="mb-2 text-xs font-medium text-muted-foreground">
                {t("sca.issues.usage.snippetTitle", { defaultValue: "Code snippet" })}
              </div>
              {!selectedEntry ? (
                <p className="text-sm text-muted-foreground">
                  {t("sca.issues.usage.selectHint", { defaultValue: "Select an entry to view its code snippet." })}
                </p>
              ) : snippetQuery.isLoading ? (
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Spinner size={16} />
                  {t("common.loading")}
                </div>
              ) : snippetQuery.isError ? (
                <p className="text-sm text-destructive">
                  {t("sca.issues.usage.snippetError", { defaultValue: "Failed to load code snippet." })}
                </p>
              ) : (
                <CodeSnippetView snippet={snippetQuery.data ?? null} />
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function buildUsageEntryId(entry: ScaUsageEntry): string {
  return [
    entry.file ?? "",
    entry.startLine ?? entry.line ?? "",
    entry.startCol ?? "",
    entry.kind ?? "",
    entry.receiver ?? "",
    entry.callee ?? "",
    entry.symbol ?? "",
  ].join("|")
}
