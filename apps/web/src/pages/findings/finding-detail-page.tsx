import { useEffect, useMemo, useState } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { CodeSnippetView } from "@/components/code-snippet/code-snippet-view"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { SeverityBadge, StatusBadge } from "@/components/status-badges"
import { getFindingDetail, getFindingSnippet, listFindingsByProject, updateFindingStatus } from "@/services/finding-service"
import { exportReport, exportTaskReport, exportProjectReport, exportRepoReport } from "@/services/report-service"
import { addItemsToTicketDraft } from "@/services/ticket-draft-service"
import type {
  DataFlowEdge,
  DataFlowNode,
  FindingLocationSummary,
  FindingStatus,
  FindingReviewOpinionText,
} from "@/types/api"

const FINDING_STATUS_OPTIONS: FindingStatus[] = ["OPEN", "CONFIRMED", "FALSE_POSITIVE", "RESOLVED", "WONT_FIX"]

export function FindingDetailPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { findingId } = useParams<{ findingId: string }>()
  const queryClient = useQueryClient()
  const [listPage, setListPage] = useState({ limit: 10, offset: 0 })
  const [exportOpen, setExportOpen] = useState(false)
  const [exportFormat, setExportFormat] = useState<"PDF" | "DOCX" | "HTML">("PDF")
  const [exportDetail, setExportDetail] = useState<"SUMMARY" | "FULL">("SUMMARY")
  const [includeCode, setIncludeCode] = useState(true)
  const [includeDataFlow, setIncludeDataFlow] = useState(true)
  const [reportTitle, setReportTitle] = useState("")
  const [exportScope, setExportScope] = useState<"finding" | "task" | "project" | "repo">("finding")
  const [exportLocale, setExportLocale] = useState("en")
  const [stashError, setStashError] = useState<string | null>(null)

  const detailQuery = useQuery({
    queryKey: ["finding", findingId, "detail"],
    queryFn: () => getFindingDetail(findingId!),
    enabled: Boolean(findingId),
  })

  const detail = detailQuery.data
  const review = detail?.review ?? null
  const uiLang: "zh" | "en" = i18n.language?.startsWith("zh") ? "zh" : "en"
  const opinion: FindingReviewOpinionText | null =
    review?.opinionI18n?.[uiLang] ?? review?.opinionI18n?.en ?? review?.opinionI18n?.zh ?? null
  const opinionSummary = opinion?.summary ?? null
  const opinionFixHint = opinion?.fixHint ?? null
  const repoId = detail?.repoId ?? null
  const projectId = detail?.projectId
  const hasDataFlow = (detail?.dataFlowNodes?.length ?? 0) > 0 || (detail?.dataFlowEdges?.length ?? 0) > 0
  const projectFindingsQuery = useQuery({
    queryKey: ["project", projectId, "findings", listPage],
    queryFn: () => listFindingsByProject(projectId!, listPage),
    enabled: Boolean(projectId),
  })

  const relatedPage = projectFindingsQuery.data ?? {
    items: [],
    total: 0,
    limit: listPage.limit,
    offset: listPage.offset,
  }
  const relatedFindings = useMemo(() => relatedPage.items ?? [], [relatedPage.items])
  const relatedTotal = relatedPage.total ?? 0
  const relatedLimit = relatedPage.limit ?? listPage.limit
  const relatedOffset = relatedPage.offset ?? listPage.offset
  const relatedPageIndex = relatedTotal === 0 ? 0 : Math.floor(relatedOffset / Math.max(1, relatedLimit))
  const relatedPageCount = Math.max(1, Math.ceil(Math.max(1, relatedTotal) / Math.max(1, relatedLimit)))
  const canPrev = relatedOffset > 0
  const canNext = relatedOffset + relatedLimit < relatedTotal

  const statusMutation = useMutation({
    mutationFn: (status: FindingStatus) => updateFindingStatus(detail!.findingId, { status }),
    onMutate: async (status) => {
      await queryClient.cancelQueries({ queryKey: ["finding", findingId, "detail"] })
      await queryClient.cancelQueries({ queryKey: ["project", projectId, "findings"] })
      const prevDetail = queryClient.getQueryData<typeof detail>(["finding", findingId, "detail"])
      const prevList = queryClient.getQueryData<typeof relatedPage>(["project", projectId, "findings", listPage])
      if (prevDetail) {
        queryClient.setQueryData(["finding", findingId, "detail"], { ...prevDetail, status })
      }
      if (prevList?.items) {
        const nextItems = prevList.items.map((it) =>
          it.findingId === detail?.findingId ? { ...it, status } : it
        )
        queryClient.setQueryData(["project", projectId, "findings", listPage], {
          ...prevList,
          items: nextItems,
        })
      }
      return { prevDetail, prevList }
    },
    onError: (_err, _status, ctx) => {
      if (ctx?.prevDetail) {
        queryClient.setQueryData(["finding", findingId, "detail"], ctx.prevDetail)
      }
      if (ctx?.prevList) {
        queryClient.setQueryData(["project", projectId, "findings", listPage], ctx.prevList)
      }
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: ["finding", findingId, "detail"] })
      await queryClient.invalidateQueries({ queryKey: ["project", projectId, "findings"] })
    },
  })

  const stashToTicketMutation = useMutation({
    mutationFn: () => addItemsToTicketDraft({ items: [{ type: "FINDING", id: detail!.findingId }] }),
    onError: (err: unknown) => {
      setStashError(err instanceof Error ? err.message : "unknown")
    },
    onSuccess: () => {
      setStashError(null)
    },
  })

  const exportMutation = useMutation({
    mutationFn: async () => {
      if (!detail) return
      const payload = {
        findingIds: exportScope === "finding" ? [detail.findingId] : [],
        format: exportFormat,
        detailLevel: exportDetail,
        includeCode,
        includeDataFlow,
        title: reportTitle || undefined,
        locale: exportLocale,
      } as const
      const response = await ((): Promise<Awaited<ReturnType<typeof exportReport>>> => {
        switch (exportScope) {
          case "task":
            return exportTaskReport(detail.taskId, payload)
          case "project":
            return exportProjectReport(detail.projectId, payload)
          case "repo":
            return repoId ? exportRepoReport(detail.projectId, repoId, payload) : exportReport(payload)
          default:
            return exportReport(payload)
        }
      })()
      const blob = new Blob([response.data], { type: response.headers["content-type"] || "application/octet-stream" })
      const url = window.URL.createObjectURL(blob)
      const disposition = response.headers["content-disposition"]
      const filename =
        disposition?.split("filename=")?.[1]?.replaceAll("\"", "") || `secrux-report.${exportFormat.toLowerCase()}`
      const link = document.createElement("a")
      link.href = url
      link.download = filename
      link.click()
      window.URL.revokeObjectURL(url)
    },
  })

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
        <p className="text-sm text-destructive">{t("findings.error")}</p>
      </div>
    )
  }

  const titleSuffix = detail.taskName ?? null

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
            <Badge variant="outline">{t("findings.detailBadge", { defaultValue: "CODE" })}</Badge>
          </div>
          <h1 className="mt-2 break-all text-2xl font-semibold">
            {detail.ruleId || t("findings.untitledRule", { defaultValue: "Untitled rule" })}
            {titleSuffix ? ` · ${titleSuffix}` : null}
          </h1>
          <p className="break-all text-sm text-muted-foreground">
            {t("findings.detailPath", {
              path: detail.location?.path ?? t("findings.locationUnknown", { defaultValue: "未知路径" }),
              line: detail.location?.line ?? detail.location?.startLine ?? "—",
              defaultValue: "{{path}} @ line {{line}}",
            })}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select
            className="h-10 rounded-md border border-input bg-background px-3 text-sm"
            value={detail.status}
            disabled={statusMutation.isPending}
            onChange={(event) => statusMutation.mutate(event.target.value as FindingStatus)}
          >
            {FINDING_STATUS_OPTIONS.map((status) => (
              <option key={status} value={status}>
                {t(`statuses.${status}`, { defaultValue: status })}
              </option>
            ))}
          </select>
          <Button
            size="sm"
            variant="outline"
            disabled={stashToTicketMutation.isPending}
            onClick={() => stashToTicketMutation.mutate()}
          >
            {stashToTicketMutation.isPending
              ? t("common.loading")
              : t("tickets.draft.addSelected", { defaultValue: "暂存到工单" })}
          </Button>
          <Dialog open={exportOpen} onOpenChange={setExportOpen}>
            <DialogTrigger asChild>
              <Button size="sm" variant="outline">
                {t("findings.exportReport", { defaultValue: "导出报告" })}
              </Button>
            </DialogTrigger>
            <DialogContent className="max-w-lg">
              <DialogHeader>
                <DialogTitle>{t("findings.exportReport", { defaultValue: "导出报告" })}</DialogTitle>
              </DialogHeader>
              <div className="space-y-4">
                <div className="space-y-2">
                  <Label>{t("findings.exportTitle", { defaultValue: "报告标题" })}</Label>
                  <Input
                    value={reportTitle}
                    placeholder={t("findings.exportTitlePlaceholder", { defaultValue: "Secrux 安全报告" })}
                    onChange={(e) => setReportTitle(e.target.value)}
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-2">
                    <Label>{t("findings.exportScope", { defaultValue: "导出范围" })}</Label>
                    <Select value={exportScope} onValueChange={(v) => setExportScope(v as typeof exportScope)}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="finding">
                          {t("findings.exportScopeFinding", { defaultValue: "仅当前漏洞" })}
                        </SelectItem>
                        <SelectItem value="task">
                          {t("findings.exportScopeTask", { defaultValue: "该任务全部漏洞" })}
                        </SelectItem>
                        <SelectItem value="project">
                          {t("findings.exportScopeProject", { defaultValue: "该项目全部漏洞" })}
                        </SelectItem>
                        <SelectItem value="repo" disabled={!repoId}>
                          {t("findings.exportScopeRepo", { defaultValue: "该仓库全部漏洞" })}
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>{t("findings.exportFormat", { defaultValue: "格式" })}</Label>
                    <Select value={exportFormat} onValueChange={(v) => setExportFormat(v as typeof exportFormat)}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="PDF">PDF</SelectItem>
                        <SelectItem value="DOCX">DOCX</SelectItem>
                        <SelectItem value="HTML">HTML</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>{t("findings.exportDetail", { defaultValue: "详情程度" })}</Label>
                    <Select value={exportDetail} onValueChange={(v) => setExportDetail(v as typeof exportDetail)}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="SUMMARY">{t("findings.exportSummary", { defaultValue: "简略" })}</SelectItem>
                        <SelectItem value="FULL">{t("findings.exportFull", { defaultValue: "详细" })}</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-2">
                    <Label>{t("findings.exportLanguage", { defaultValue: "语言" })}</Label>
                    <Select value={exportLocale} onValueChange={(v) => setExportLocale(v)}>
                      <SelectTrigger>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="en">English</SelectItem>
                        <SelectItem value="zh">中文</SelectItem>
                      </SelectContent>
                    </Select>
                  </div>
                </div>
                <div className="space-y-2">
                  <Label>{t("findings.exportInclude", { defaultValue: "包含内容" })}</Label>
                  <div className="flex flex-col gap-2 text-sm">
                    <label className="flex items-center gap-2">
                      <Checkbox checked={includeCode} onChange={(e) => setIncludeCode(e.target.checked)} />
                      {t("findings.exportCode", { defaultValue: "代码片段" })}
                    </label>
                    <label className="flex items-center gap-2">
                      <Checkbox checked={includeDataFlow} onChange={(e) => setIncludeDataFlow(e.target.checked)} />
                      {t("findings.exportDataFlow", { defaultValue: "数据流" })}
                    </label>
                  </div>
                </div>
                <div className="flex items-center justify-end gap-2">
                  <Button variant="ghost" onClick={() => setExportOpen(false)}>
                    {t("common.cancel")}
                  </Button>
                  <Button
                    onClick={() => exportMutation.mutate()}
                    disabled={exportMutation.isPending}
                  >
                    {exportMutation.isPending ? t("common.loading") : t("findings.exportReport", { defaultValue: "导出报告" })}
                  </Button>
                </div>
              </div>
            </DialogContent>
          </Dialog>
        </div>
      </div>

      {stashError ? <p className="text-sm text-destructive">{stashError}</p> : null}

      <div className="grid gap-4 lg:grid-cols-[320px,1fr]">
        <Card className="h-full">
          <CardHeader>
            <CardTitle className="text-base">{t("findings.detailSection.related")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {projectFindingsQuery.isLoading ? (
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Spinner size={16} />
                {t("common.loading")}
              </div>
            ) : relatedFindings.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("findings.empty")}</p>
            ) : (
              <div className="space-y-2">
                {relatedFindings.map((item) => (
                  <button
                    key={item.findingId}
                    type="button"
                    onClick={() => navigate(`/findings/${item.findingId}`)}
                    className={`w-full rounded-md border px-3 py-2 text-left text-sm transition hover:border-primary ${
                      item.findingId === detail.findingId ? "border-primary bg-primary/5" : "border-border bg-background"
                    }`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="block max-w-[200px] truncate font-medium">
                        {item.ruleId || t("findings.untitledRule", { defaultValue: "Untitled rule" })}
                      </span>
                      <span className="truncate text-xs text-muted-foreground">{item.findingId}</span>
                    </div>
                    <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                      <StatusBadge status={item.status} />
                      <SeverityBadge severity={item.severity} />
                      {item.sourceEngine ? <span className="truncate text-muted-foreground">{item.sourceEngine}</span> : null}
                    </div>
                  </button>
                ))}
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
                      disabled={!canPrev || projectFindingsQuery.isFetching}
                      onClick={() =>
                        setListPage((prev) => ({
                          ...prev,
                          offset: Math.max(0, relatedOffset - relatedLimit),
                        }))
                      }
                    >
                      {t("common.previous")}
                    </Button>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={!canNext || projectFindingsQuery.isFetching}
                      onClick={() =>
                        setListPage((prev) => ({
                          ...prev,
                          offset: Math.min(relatedTotal, relatedOffset + relatedLimit),
                        }))
                      }
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
          {!hasDataFlow ? (
            <Card>
              <CardHeader>
                <CardTitle className="text-base">{t("findings.detailSection.code")}</CardTitle>
              </CardHeader>
              <CardContent>
                <CodeSnippetView snippet={detail.codeSnippet} />
              </CardContent>
            </Card>
          ) : null}

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("findings.detailSection.dataflow")}</CardTitle>
            </CardHeader>
            <CardContent>
              <DataFlowGraphView findingId={detail.findingId} nodes={detail.dataFlowNodes} edges={detail.dataFlowEdges} />
            </CardContent>
          </Card>

          {review?.reviewType === "AI" ? (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center justify-between gap-2 text-base">
                  <span>{t("findings.detailSection.aiReviewOpinion", { defaultValue: "AI 审核意见" })}</span>
                  <Badge variant="secondary">{t("findings.aiReviewBadge", { defaultValue: "AI审核" })}</Badge>
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-muted-foreground">
                    {t("findings.aiReviewVerdict", { defaultValue: "结论" })}:
                  </span>
                  <span>{review.verdict}</span>
                  {typeof review.confidence === "number" ? (
                    <span className="text-muted-foreground">
                      · {t("findings.aiReviewConfidence", { defaultValue: "置信度" })}: {review.confidence.toFixed(2)}
                    </span>
                  ) : null}
                </div>
                <div>
                  <div className="text-muted-foreground">{t("findings.aiReviewSummary", { defaultValue: "摘要" })}</div>
                  <div className="mt-1 whitespace-pre-wrap break-words">{opinionSummary ?? "—"}</div>
                </div>
                <div>
                  <div className="text-muted-foreground">{t("findings.aiReviewFixHint", { defaultValue: "修复建议" })}</div>
                  <div className="mt-1 whitespace-pre-wrap break-words">{opinionFixHint ?? "—"}</div>
                </div>
              </CardContent>
            </Card>
          ) : null}

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("findings.detailSection.meta")}</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-4 md:grid-cols-2">
              <div className="space-y-3 text-sm">
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">{t("findings.labels.task", { defaultValue: "Task" })}</div>
                  <div className="break-all text-xs">{detail.taskName ?? "—"}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">
                    {t("findings.labels.project", { defaultValue: "Project" })}
                  </div>
                  <div className="break-all text-xs">{detail.projectName ?? "—"}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">
                    {t("findings.labels.repository", { defaultValue: "Repository" })}
                  </div>
                  <div className="break-all text-xs">{detail.repoRemoteUrl ?? "—"}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">{t("findings.columns.rule")}</div>
                  <div className="break-all text-xs">{detail.ruleId ?? "—"}</div>
                </div>
              </div>
              <div className="space-y-3 text-sm">
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">{t("findings.columns.location")}</div>
                  <div className="break-all font-mono text-xs">{formatFindingLocation(detail.location) ?? "—"}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">{t("findings.columns.introducedBy")}</div>
                  <div className="break-all font-mono text-xs">{detail.sourceEngine}</div>
                </div>
                <div className="space-y-1">
                  <div className="text-xs font-medium text-muted-foreground">
                    {t("findings.labels.createdAt", { defaultValue: "Created" })}
                  </div>
                  <div className="text-xs text-muted-foreground">{new Date(detail.createdAt).toLocaleString()}</div>
                </div>
                {detail.updatedAt ? (
                  <div className="space-y-1">
                    <div className="text-xs font-medium text-muted-foreground">
                      {t("common.updatedAt", { defaultValue: "Updated at" })}
                    </div>
                    <div className="text-xs text-muted-foreground">{new Date(detail.updatedAt).toLocaleString()}</div>
                  </div>
                ) : null}
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  )
}

function DataFlowGraphView({
  findingId,
  nodes,
  edges,
}: {
  findingId: string
  nodes: DataFlowNode[]
  edges: DataFlowEdge[]
}) {
  const { t } = useTranslation()
  const nodesById = useMemo(() => new Map(nodes.map((n) => [n.id, n])), [nodes])
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const selectedNode = selectedNodeId ? nodesById.get(selectedNodeId) ?? null : null
  const [snippetExpanded, setSnippetExpanded] = useState(true)

  const paths = useMemo(() => buildDataflowPaths(nodes, edges), [nodes, edges])
  const hasAny = nodes.length > 0 || edges.length > 0
  const hasPaths = paths.length > 0
  const defaultNodeId = paths[0]?.nodes?.[0] ?? nodes[0]?.id ?? null

  useEffect(() => {
    if (!defaultNodeId) return
    if (!selectedNodeId || !nodesById.has(selectedNodeId)) {
      setSelectedNodeId(defaultNodeId)
    }
  }, [defaultNodeId, nodesById, selectedNodeId])

  useEffect(() => {
    if (selectedNode?.file && selectedNode?.line) {
      setSnippetExpanded(true)
    }
  }, [selectedNode?.file, selectedNode?.line])

  const handleNodeClick = (nodeId: string) => {
    setSelectedNodeId((prev) => {
      if (prev === nodeId) {
        setSnippetExpanded((v) => !v)
        return prev
      }
      setSnippetExpanded(true)
      return nodeId
    })
  }

  const snippetQuery = useQuery({
    queryKey: ["finding", findingId, "snippet", selectedNode?.file, selectedNode?.line],
    queryFn: () =>
      getFindingSnippet(findingId, {
        path: selectedNode!.file!,
        line: selectedNode!.line!,
        context: 6,
      }),
    enabled: Boolean(selectedNode?.file && selectedNode?.line),
  })

  if (!hasAny) {
    return <p className="text-sm text-muted-foreground">{t("findings.detailSection.noDataflow")}</p>
  }

  if (!hasPaths) {
    return (
      <div className="space-y-2">
        <p className="text-sm text-muted-foreground">{t("findings.detailSection.dataflowNodesOnly")}</p>
        <div className="flex flex-wrap gap-2">
          {nodes.map((node) => (
            <Badge key={node.id} variant="outline" className="flex items-center gap-1 text-xs">
              <span className="font-semibold">{node.label}</span>
              {node.file ? (
                <span className="text-muted-foreground">{node.file}{node.line ? `:${node.line}` : ""}</span>
              ) : null}
            </Badge>
          ))}
        </div>
      </div>
    )
  }

  return (
    <div className="min-w-0 space-y-3">
      <p className="text-xs text-muted-foreground">
        {t("findings.dataflow.clickNodeHint", { defaultValue: "点击节点查看对应代码片段" })}
      </p>
      <div className="space-y-4">
        {paths.map((path, idx) => (
          <div key={`path-${idx}`} className="min-w-0 rounded-md border bg-muted/20 p-3">
            <div className="mb-3 flex items-center justify-between">
              <div className="text-xs font-medium text-muted-foreground">
                {t("findings.dataflow.path", { defaultValue: "链路" })} {idx + 1}
              </div>
              <Badge variant="secondary" className="text-xs">
                {path.nodes.length} {t("findings.dataflow.nodes", { defaultValue: "节点" })}
              </Badge>
            </div>
            <p className="mb-3 break-words text-sm text-muted-foreground">
              {buildDataflowSummary(
                path.nodes.map((id) => nodesById.get(id)).filter(Boolean) as DataFlowNode[],
                t
              )}
            </p>
            <div className="space-y-2">
              {path.nodes.map((nodeId, nodeIdx) => {
                const node = nodesById.get(nodeId)
                if (!node) return null
                const nextNodeId = path.nodes[nodeIdx + 1]
                const edge = nextNodeId ? path.edges.get(`${nodeId}→${nextNodeId}`) ?? null : null
                const isSelected = selectedNodeId === node.id
                const canShowSnippet = isSelected && snippetExpanded && node.file && node.line
                const role = (node.role ?? "UNKNOWN").toUpperCase()
                const roleLabel = t(`findings.dataflow.roles.${role}`, { defaultValue: role })
                const displayValue = node.value ?? node.label
                const locationText = formatNodeLocation(node)
                return (
                  <div key={node.id} className="space-y-2">
                    <button
                      type="button"
                      onClick={() => handleNodeClick(node.id)}
                      className={`w-full min-w-0 rounded-md border px-3 py-2 text-left transition ${
                        isSelected ? "border-primary bg-primary/5" : "bg-background hover:bg-muted/30"
                      }`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div className="min-w-0">
                          <div className="flex min-w-0 items-center gap-2">
                            <span className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary">
                              {nodeIdx + 1}
                            </span>
                            <Badge variant="secondary" className="shrink-0 text-[11px]">
                              {roleLabel}
                            </Badge>
                            <span className="min-w-0 break-all font-medium">{displayValue}</span>
                          </div>
                          {locationText ? (
                            <div className="mt-1 break-all text-xs text-muted-foreground">
                              {t("findings.dataflow.location", { defaultValue: "位置" })}: {locationText}
                            </div>
                          ) : null}
                        </div>
                        <span className="shrink-0 text-xs text-muted-foreground">
                          {isSelected
                            ? snippetExpanded
                              ? t("findings.dataflow.collapse", { defaultValue: "收起" })
                              : t("findings.dataflow.expand", { defaultValue: "展开" })
                            : ""}
                        </span>
                      </div>
                    </button>

                    {canShowSnippet ? (
                      <div className="min-w-0 rounded-md border bg-background p-3">
                        <div className="mb-2 text-xs font-medium text-muted-foreground">
                          {t("findings.dataflow.snippetTitle", { defaultValue: "节点代码片段" })}
                        </div>
                        {snippetQuery.isLoading ? (
                          <div className="flex items-center gap-2 text-sm text-muted-foreground">
                            <Spinner size={16} />
                            {t("common.loading")}
                          </div>
                        ) : (
                          <CodeSnippetView snippet={snippetQuery.data ?? null} />
                        )}
                      </div>
                    ) : null}

                    {isSelected && snippetExpanded && !(node.file && node.line) ? (
                      <p className="text-sm text-muted-foreground">
                        {t("findings.dataflow.selectNode", { defaultValue: "请选择包含文件与行号的节点查看代码" })}
                      </p>
                    ) : null}

                    {nextNodeId ? (
                      <div className="flex items-center gap-3 pl-8">
                        <div className="flex flex-col items-center text-muted-foreground">
                          <div className="h-4 w-px bg-border" />
                          <div className="text-[10px] leading-4">
                            {formatDataflowEdgeLabel(
                              edge?.label,
                              t("findings.dataflow.flowTo", { defaultValue: "流向" }),
                              t("findings.detailSection.dataflow", { defaultValue: "数据流" })
                            )}
                          </div>
                          <div className="h-4 w-px bg-border" />
                          <div className="text-xs">↓</div>
                        </div>
                        <div className="text-xs text-muted-foreground">
                          {t("findings.dataflow.toNext", { defaultValue: "到下一步" })}
                        </div>
                      </div>
                    ) : null}
                  </div>
                )
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function buildDataflowPaths(nodes: DataFlowNode[], edges: DataFlowEdge[]) {
  const nodesById = new Map(nodes.map((n) => [n.id, n]))
  const outgoing = new Map<string, string[]>()
  const inDegree = new Map<string, number>()
  const edgeByKey = new Map<string, DataFlowEdge>()

  for (const node of nodes) {
    inDegree.set(node.id, 0)
    outgoing.set(node.id, [])
  }

  for (const edge of edges) {
    if (!nodesById.has(edge.source) || !nodesById.has(edge.target)) continue
    outgoing.get(edge.source)?.push(edge.target)
    inDegree.set(edge.target, (inDegree.get(edge.target) ?? 0) + 1)
    edgeByKey.set(`${edge.source}→${edge.target}`, edge)
  }

  const starts = nodes
    .map((n) => n.id)
    .filter((id) => (inDegree.get(id) ?? 0) === 0)

  const visitedGlobal = new Set<string>()
  const paths: Array<{ nodes: string[]; edges: Map<string, DataFlowEdge> }> = []

  const follow = (startId: string) => {
    const pathNodes: string[] = []
    const usedEdges = new Map<string, DataFlowEdge>()
    let current: string | null = startId
    const visited = new Set<string>()
	    while (current && !visited.has(current) && pathNodes.length < 50) {
	      visited.add(current)
	      visitedGlobal.add(current)
	      pathNodes.push(current)
	      const nextNodeId: string | null = outgoing.get(current)?.[0] ?? null
	      if (!nextNodeId) break
	      const key = `${current}→${nextNodeId}`
	      const edge = edgeByKey.get(key)
	      if (edge) usedEdges.set(key, edge)
	      current = nextNodeId
	    }
    return { nodes: pathNodes, edges: usedEdges }
  }

  if (starts.length > 0) {
    for (const start of starts) {
      const p = follow(start)
      if (p.nodes.length > 0) paths.push(p)
    }
  }

  for (const node of nodes) {
    if (visitedGlobal.has(node.id)) continue
    const p = follow(node.id)
    if (p.nodes.length > 0) paths.push(p)
  }

  return paths
}

function formatDataflowEdgeLabel(label: string | null | undefined, fallback: string, dataflowLabel: string) {
  const value = (label ?? "").trim()
  if (!value) return fallback
  if (value.toLowerCase() === "dataflow") return dataflowLabel
  return value
}

function formatNodeLocation(node: DataFlowNode) {
  const file = node.file ?? null
  const line = typeof node.line === "number" ? node.line : null
  if (!file) return null
  if (!line) return file
  const startColumn = typeof node.startColumn === "number" ? node.startColumn : null
  const endColumn = typeof node.endColumn === "number" ? node.endColumn : null
  const colSuffix =
    startColumn && endColumn
      ? `:${startColumn}-${endColumn}`
      : startColumn
        ? `:${startColumn}`
        : ""
  return `${file}:${line}${colSuffix}`
}

function formatFindingLocation(location: FindingLocationSummary | null | undefined) {
  const path = (location?.path ?? "").trim()
  if (!path) return null
  const line = typeof location?.line === "number" ? location.line : typeof location?.startLine === "number" ? location.startLine : null
  const startColumn = typeof location?.startColumn === "number" ? location.startColumn : null
  const endColumn = typeof location?.endColumn === "number" ? location.endColumn : null
  if (!line) return path
  const colSuffix =
    startColumn && endColumn
      ? `:${startColumn}-${endColumn}`
      : startColumn
        ? `:${startColumn}`
        : ""
  return `${path}:${line}${colSuffix}`
}

function buildDataflowSummary(nodes: DataFlowNode[], t: (key: string, options?: any) => string) {
  if (!nodes.length) return ""
  const asValue = (node: DataFlowNode) => (node.value ?? node.label ?? node.id).trim()
  const findRole = (role: string) => nodes.find((n) => (n.role ?? "").toUpperCase() === role) ?? null
  const source = findRole("SOURCE") ?? nodes[0]
  const sink = [...nodes].reverse().find((n) => (n.role ?? "").toUpperCase() === "SINK") ?? nodes[nodes.length - 1]
  const middle = nodes.filter((n) => n.id !== source.id && n.id !== sink.id)
  const parts: string[] = []
  parts.push(t("findings.dataflow.summary.start", { value: asValue(source) }))
  for (const node of middle) {
    parts.push(t("findings.dataflow.summary.propagate", { value: asValue(node) }))
  }
  if (sink.id !== source.id) {
    parts.push(t("findings.dataflow.summary.end", { value: asValue(sink) }))
  }
  const sep = t("findings.dataflow.summary.separator", { defaultValue: ", " })
  const punct = t("findings.dataflow.summary.punct", { defaultValue: "." })
  return `${parts.join(sep)}${punct}`
}
