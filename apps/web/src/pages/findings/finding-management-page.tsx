import { useEffect, useMemo, useState } from "react"
import { useLocation, useNavigate } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { TaskListPanel } from "@/components/task-list-panel"
import { FindingAiReviewDialog } from "@/components/findings/finding-ai-review-dialog"
import { FindingBatchBar } from "@/components/findings/finding-batch-bar"
import { FindingFilterBar } from "@/components/findings/finding-filter-bar"
import { FindingSummaryChips } from "@/components/findings/finding-summary-chips"
import { FindingTable } from "@/components/findings/finding-table"
import { ProjectListPanel } from "@/components/findings/project-list-panel"
import { RepositoryListPanel } from "@/components/findings/repository-list-panel"
import { type FindingLocalFilters, toFindingListFilters } from "@/components/findings/finding-filters"

import { useProjectsQuery } from "@/hooks/queries/use-projects"
import { useTasksQuery } from "@/hooks/queries/use-tasks"
import { listRepositories } from "@/services/project-service"
import { listFindingsByProject, listFindingsByRepo, triggerFindingAiReview, updateFindingStatus, updateFindingStatusBatch } from "@/services/finding-service"
import { getTaskFindings } from "@/services/task-service"
import { addItemsToTicketDraft } from "@/services/ticket-draft-service"
import type { AiJobTicket, FindingStatus, FindingSummary, PageResponse, RepositoryResponse, TaskListFilters, TaskSummary } from "@/types/api"

type FindingBrowseScope = "TASK" | "PROJECT" | "REPO"

export function FindingManagementPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()

  const [scope, setScope] = useState<FindingBrowseScope>("TASK")

  const [taskFilters, setTaskFilters] = useState<TaskListFilters>({ limit: 25, excludeType: "SCA_CHECK" })
  const tasksQuery = useTasksQuery(taskFilters, { enabled: scope === "TASK" })
  const taskItems = useMemo(() => tasksQuery.data?.items ?? [], [tasksQuery.data])
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null)

  useEffect(() => {
    if (scope !== "TASK") return
    if (taskItems.length === 0) {
      setSelectedTaskId(null)
      return
    }
    if (!selectedTaskId || !taskItems.some((task) => task.taskId === selectedTaskId)) {
      setSelectedTaskId(taskItems[0].taskId)
    }
  }, [scope, selectedTaskId, taskItems])

  const selectedTask: TaskSummary | null = useMemo(() => {
    return taskItems.find((task) => task.taskId === selectedTaskId) ?? null
  }, [taskItems, selectedTaskId])
  const shouldPoll = scope === "TASK" && (selectedTask?.status === "RUNNING" || selectedTask?.status === "PENDING")

  const projectsQuery = useProjectsQuery()
  const projectItems = useMemo(() => projectsQuery.data ?? [], [projectsQuery.data])
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null)

  useEffect(() => {
    if (scope !== "PROJECT" && scope !== "REPO") return
    if (projectItems.length === 0) {
      setSelectedProjectId(null)
      return
    }
    if (!selectedProjectId || !projectItems.some((project) => project.projectId === selectedProjectId)) {
      setSelectedProjectId(projectItems[0].projectId)
    }
  }, [scope, projectItems, selectedProjectId])

  const selectedProject = useMemo(() => {
    return projectItems.find((project) => project.projectId === selectedProjectId) ?? null
  }, [projectItems, selectedProjectId])

  const repositoriesQuery = useQuery<RepositoryResponse[]>({
    queryKey: ["project", selectedProjectId, "repositories"],
    queryFn: () => listRepositories(selectedProjectId!),
    enabled: scope === "REPO" && Boolean(selectedProjectId),
    staleTime: 30_000,
  })

  const repoItems = useMemo(() => repositoriesQuery.data ?? [], [repositoriesQuery.data])
  const [selectedRepoId, setSelectedRepoId] = useState<string | null>(null)

  useEffect(() => {
    const params = new URLSearchParams(location.search)
    const scopeParam = params.get("scope") as FindingBrowseScope | null
    const taskIdParam = params.get("taskId")
    const projectIdParam = params.get("projectId")
    const repoIdParam = params.get("repoId")

    if (scopeParam === "TASK" || scopeParam === "PROJECT" || scopeParam === "REPO") {
      setScope(scopeParam)
    }
    if (taskIdParam) {
      setSelectedTaskId(taskIdParam)
    }
    if (projectIdParam) {
      setSelectedProjectId(projectIdParam)
    }
    if (repoIdParam) {
      setSelectedRepoId(repoIdParam)
    }
  }, [location.search])

  useEffect(() => {
    if (scope !== "REPO") return
    if (!selectedProjectId) {
      setSelectedRepoId(null)
      return
    }
    if (repoItems.length === 0) {
      setSelectedRepoId(null)
      return
    }
    if (!selectedRepoId || !repoItems.some((repo) => repo.repoId === selectedRepoId)) {
      setSelectedRepoId(repoItems[0].repoId)
    }
  }, [repoItems, scope, selectedProjectId, selectedRepoId])

  const selectedRepo = useMemo(() => {
    return repoItems.find((repo) => repo.repoId === selectedRepoId) ?? null
  }, [repoItems, selectedRepoId])

  const [findingFilters, setFindingFilters] = useState<FindingLocalFilters>({
    severity: "ALL",
    status: "ALL",
    search: "",
  })

  const [findingPage, setFindingPage] = useState<{ limit: number; offset: number }>({ limit: 15, offset: 0 })
  const [selectedFindingIds, setSelectedFindingIds] = useState<Set<string>>(new Set())
  const [batchStatus, setBatchStatus] = useState<FindingStatus>("CONFIRMED")
  const [batchReason, setBatchReason] = useState("")

  const filtersForQuery = useMemo(() => toFindingListFilters(findingFilters), [findingFilters])

  const selectionKey = useMemo(() => {
    if (scope === "TASK") return selectedTaskId ?? "none"
    if (scope === "PROJECT") return selectedProjectId ?? "none"
    return selectedProjectId && selectedRepoId ? `${selectedProjectId}:${selectedRepoId}` : "none"
  }, [scope, selectedProjectId, selectedRepoId, selectedTaskId])

  useEffect(() => {
    setFindingPage((prev) => ({ ...prev, offset: 0 }))
    setSelectedFindingIds(new Set())
  }, [selectionKey])

  const findingsQuery = useQuery<PageResponse<FindingSummary>>({
    queryKey: ["findings", scope, selectionKey, findingPage, filtersForQuery],
    queryFn: () => {
      if (scope === "TASK") {
        return getTaskFindings(selectedTaskId!, { ...findingPage, ...filtersForQuery })
      }
      if (scope === "PROJECT") {
        return listFindingsByProject(selectedProjectId!, { ...findingPage, ...filtersForQuery })
      }
      return listFindingsByRepo(selectedProjectId!, selectedRepoId!, { ...findingPage, ...filtersForQuery })
    },
    enabled:
      (scope === "TASK" && Boolean(selectedTaskId)) ||
      (scope === "PROJECT" && Boolean(selectedProjectId)) ||
      (scope === "REPO" && Boolean(selectedProjectId && selectedRepoId)),
    refetchInterval: shouldPoll ? 7000 : false,
  })

  const findings = findingsQuery.data?.items ?? []
  const findingsTotal = findingsQuery.data?.total ?? 0
  const findingsLimit = findingsQuery.data?.limit ?? findingPage.limit
  const findingsOffset = findingsQuery.data?.offset ?? findingPage.offset
  const findingsPageIndex = findingsTotal === 0 ? 0 : Math.floor(findingsOffset / Math.max(1, findingsLimit))
  const findingsPageCount = Math.max(1, Math.ceil(Math.max(1, findingsTotal) / Math.max(1, findingsLimit)))
  const canPrevFindings = findingsOffset > 0
  const canNextFindings = findingsOffset + findingsLimit < findingsTotal

  const changeFindingPage = (direction: "prev" | "next") => {
    if (direction === "prev" && !canPrevFindings) return
    if (direction === "next" && !canNextFindings) return
    const nextOffset =
      direction === "prev" ? Math.max(0, findingsOffset - findingsLimit) : Math.min(findingsTotal, findingsOffset + findingsLimit)
    setFindingPage({
      limit: findingsLimit,
      offset: nextOffset,
    })
  }

  const updateStatusMutation = useMutation<FindingSummary, unknown, { findingId: string; status: FindingStatus }>({
    mutationFn: ({ findingId, status }: { findingId: string; status: FindingStatus }) => updateFindingStatus(findingId, { status }),
    onSuccess: () => {
      void findingsQuery.refetch()
    },
  })

  const batchUpdateMutation = useMutation({
    mutationFn: (payload: { findingIds: string[]; status: FindingStatus; reason?: string; fixVersion?: string }) =>
      updateFindingStatusBatch(payload),
    onSuccess: () => {
      setSelectedFindingIds(new Set())
      setBatchReason("")
      void findingsQuery.refetch()
    },
  })

  const stashToTicketMutation = useMutation({
    mutationFn: (findingIds: string[]) => addItemsToTicketDraft({ items: findingIds.map((id) => ({ type: "FINDING", id })) }),
    onSuccess: () => {
      setSelectedFindingIds(new Set())
    },
  })

  const [aiReviewOpen, setAiReviewOpen] = useState(false)
  const [aiReviewJobId, setAiReviewJobId] = useState<string | null>(null)
  const [aiReviewSubmitError, setAiReviewSubmitError] = useState<string | null>(null)

  const aiReviewMutation = useMutation<AiJobTicket, unknown, string>({
    mutationFn: (findingId: string) => triggerFindingAiReview(findingId),
    onMutate: () => {
      setAiReviewSubmitError(null)
      setAiReviewJobId(null)
    },
    onSuccess: (ticket) => {
      setAiReviewJobId(ticket.jobId)
      setAiReviewOpen(true)
    },
    onError: (err) => {
      setAiReviewSubmitError(
        err instanceof Error ? err.message : t("findings.aiReviewFailed", { defaultValue: "AI 复核提交失败" })
      )
      setAiReviewOpen(true)
    },
  })

  const panelTitle = useMemo(() => {
    if (scope === "TASK") {
      return selectedTask
        ? t("findings.panelTitle", { task: selectedTask.name ?? t("common.notAvailable") })
        : t("findings.noneSelected")
    }
    if (scope === "PROJECT") {
      return selectedProject
        ? t("findings.panelTitleProject", { defaultValue: "{{project}} 的漏洞列表", project: selectedProject.name })
        : t("findings.noneSelectedProject", { defaultValue: "请选择一个项目。" })
    }
    const repoLabel = selectedRepo?.remoteUrl ?? selectedRepo?.repoId ?? t("common.notAvailable")
    return selectedRepo
      ? t("findings.panelTitleRepo", { defaultValue: "{{repo}} 的漏洞列表", repo: repoLabel })
      : t("findings.noneSelectedRepo", { defaultValue: "请选择一个代码仓库。" })
  }, [scope, selectedProject, selectedRepo, selectedTask, t])

  const selectionReady =
    (scope === "TASK" && Boolean(selectedTaskId)) ||
    (scope === "PROJECT" && Boolean(selectedProjectId)) ||
    (scope === "REPO" && Boolean(selectedProjectId && selectedRepoId))

  const hintText = useMemo(() => {
    if (scope === "TASK") return t("findings.selectTaskHint")
    if (scope === "PROJECT") return t("findings.selectProjectHint", { defaultValue: "从左侧项目列表中选择一个项目以查看漏洞。" })
    return t("findings.selectRepoHint", { defaultValue: "从左侧代码仓库列表中选择一个仓库以查看漏洞。" })
  }, [scope, t])

  const showBatch = selectedFindingIds.size > 0

  return (
    <div className="space-y-4">
      <FindingAiReviewDialog
        open={aiReviewOpen}
        jobId={aiReviewJobId}
        submitError={aiReviewSubmitError}
        onCompleted={() => void findingsQuery.refetch()}
        onOpenChange={(open) => {
          setAiReviewOpen(open)
          if (!open) {
            setAiReviewJobId(null)
            setAiReviewSubmitError(null)
          }
        }}
      />

      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{t("findings.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("findings.subtitle")}</p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-muted-foreground">
            {t("findings.scope.label", { defaultValue: "查看维度" })}
          </span>
          <select
            className="h-9 rounded-md border border-input bg-background px-2 text-sm"
            value={scope}
            onChange={(event) => setScope(event.target.value as FindingBrowseScope)}
          >
            <option value="TASK">{t("findings.scope.task", { defaultValue: "按任务" })}</option>
            <option value="PROJECT">{t("findings.scope.project", { defaultValue: "按项目" })}</option>
            <option value="REPO">{t("findings.scope.repo", { defaultValue: "按代码仓库" })}</option>
          </select>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[360px,1fr]">
        {scope === "TASK" ? (
          <TaskListPanel
            tasksQuery={tasksQuery}
            filters={taskFilters}
            onFiltersChange={setTaskFilters}
            selectedTaskId={selectedTaskId}
            onSelectTask={setSelectedTaskId}
            titleKey="findings.taskList"
          />
        ) : scope === "PROJECT" ? (
          <ProjectListPanel
            projectsQuery={projectsQuery}
            selectedProjectId={selectedProjectId}
            onSelectProject={setSelectedProjectId}
            titleKey="findings.projectList"
          />
        ) : (
          <div className="space-y-4">
            <ProjectListPanel
              projectsQuery={projectsQuery}
              selectedProjectId={selectedProjectId}
              onSelectProject={(projectId) => {
                setSelectedProjectId(projectId)
                setSelectedRepoId(null)
              }}
              titleKey="findings.projectList"
            />
            <RepositoryListPanel
              repositoriesQuery={repositoriesQuery}
              selectedRepoId={selectedRepoId}
              onSelectRepo={setSelectedRepoId}
              titleKey="findings.repoList"
            />
          </div>
        )}

        <Card className="space-y-4">
          <CardHeader>
            <CardTitle className="text-base">{panelTitle}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {selectionReady ? (
              <>
                <FindingFilterBar
                  filters={findingFilters}
                  onChange={(next) => {
                    setFindingFilters(next)
                    setFindingPage((prev) => ({ ...prev, offset: 0 }))
                  }}
                />
                <FindingSummaryChips findings={findings} />
                {showBatch ? (
                  <FindingBatchBar
                    selectedCount={selectedFindingIds.size}
                    status={batchStatus}
                    reason={batchReason}
                    onStatusChange={setBatchStatus}
                    onReasonChange={setBatchReason}
                    stashPending={stashToTicketMutation.isPending}
                    applyPending={batchUpdateMutation.isPending}
                    onStash={() => stashToTicketMutation.mutate(Array.from(selectedFindingIds))}
                    onApply={() =>
                      batchUpdateMutation.mutate({
                        findingIds: Array.from(selectedFindingIds),
                        status: batchStatus,
                        reason: batchReason.trim() || undefined,
                      })
                    }
                  />
                ) : null}

                <FindingTable
                  findings={findings}
                  isLoading={findingsQuery.isLoading}
                  error={findingsQuery.isError}
                  total={findingsTotal}
                  pageIndex={findingsPageIndex}
                  pageCount={findingsPageCount}
                  canPrev={canPrevFindings}
                  canNext={canNextFindings}
                  isFetching={findingsQuery.isFetching}
                  onOpenDetail={(findingId) => navigate(`/findings/${findingId}`)}
                  onPageChange={changeFindingPage}
                  updateStatusMutation={updateStatusMutation}
                  aiReviewMutation={aiReviewMutation}
                  selectedFindingIds={selectedFindingIds}
                  onToggleFinding={(findingId) => {
                    setSelectedFindingIds((prev) => {
                      const next = new Set(prev)
                      if (next.has(findingId)) {
                        next.delete(findingId)
                      } else {
                        next.add(findingId)
                      }
                      return next
                    })
                  }}
                  onToggleAll={(checked) => {
                    setSelectedFindingIds((prev) => {
                      const next = new Set(prev)
                      if (!checked) {
                        findings.forEach((f) => next.delete(f.findingId))
                        return next
                      }
                      findings.forEach((f) => next.add(f.findingId))
                      return next
                    })
                  }}
                />
              </>
            ) : (
              <p className="text-sm text-muted-foreground">{hintText}</p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
