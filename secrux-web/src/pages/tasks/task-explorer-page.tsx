import { useEffect, useMemo, useState } from "react"
import { useLocation, useNavigate } from "react-router-dom"
import type { ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery, type UseQueryResult } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Spinner } from "@/components/ui/spinner"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import {
  getTaskArtifacts,
  getTaskFindings,
  getTaskStages,
  getStageLogs,
  runResultReviewStage,
  createTask,
  assignTask,
  deleteTask,
} from "@/services/task-service"
import { listRepositories, getRepositoryGitMetadata } from "@/services/project-service"
import { uploadScaArchive, uploadScaSbom } from "@/services/sca-service"
import type {
  ArtifactSummary,
  CreateTaskPayload,
  FindingListFilters,
  FindingSummary,
  PageResponse,
  RepositoryGitMetadata,
  RepositoryResponse,
  ResultReviewRequest,
  SourceRefType,
  StageSummary,
  TaskListFilters,
  TaskLogChunkResponse,
  TaskSpecPayload,
  TaskSummary,
  TaskType,
} from "@/types/api"
import { TaskListPanel } from "@/components/task-list-panel"
import { useTasksQuery } from "@/hooks/queries/use-tasks"
import { useProjectsQuery } from "@/hooks/queries/use-projects"
import { useExecutorsQuery } from "@/hooks/queries/use-executors"
import { StatusBadge, SeverityBadge } from "@/components/status-badges"
import { cn, formatBytes, formatDateTime, formatGbFromMb, formatPercent } from "@/lib/utils"

const TASK_TYPES: TaskType[] = ["CODE_CHECK", "SCA_CHECK"]
const ENGINE_BINDINGS: Record<TaskType, string[]> = {
  CODE_CHECK: ["semgrep"],
  SECURITY_SCAN: [],
  SUPPLY_CHAIN: [],
  SCA_CHECK: ["trivy"],
  IDE_AUDIT: [],
}

const REF_MODES = ["latest", "branch", "tag", "commit"] as const
type RefMode = (typeof REF_MODES)[number]

const SCA_TARGET_TYPES = ["git", "archive", "filesystem", "image", "sbom"] as const
type ScaTargetType = (typeof SCA_TARGET_TYPES)[number]

export function TaskExplorerPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const location = useLocation()
  const [filters, setFilters] = useState<TaskListFilters>({ limit: 25 })
  const tasksQuery = useTasksQuery(filters)
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createInitialType, setCreateInitialType] = useState<TaskType | undefined>(undefined)
  const [assignState, setAssignState] = useState<{ open: boolean; task: TaskSummary | null }>({
    open: false,
    task: null,
  })
  const [findingPage, setFindingPage] = useState<Pick<FindingListFilters, "limit" | "offset">>({
    limit: 10,
    offset: 0,
  })

  useEffect(() => {
    const state = location.state as { openCreate?: boolean; initialType?: TaskType } | null
    if (!state?.openCreate) return
    setCreateInitialType(state.initialType)
    setCreateOpen(true)
    navigate("/tasks/explorer", { replace: true, state: null })
  }, [location.state, navigate])

  useEffect(() => {
    const items = tasksQuery.data?.items ?? []
    if (items.length === 0) {
      setSelectedTaskId(null)
      return
    }
    if (!selectedTaskId || !items.some((task) => task.taskId === selectedTaskId)) {
      setSelectedTaskId(items[0].taskId)
    }
  }, [tasksQuery.data, selectedTaskId])

  useEffect(() => {
    setFindingPage((prev) => ({ ...prev, offset: 0 }))
  }, [selectedTaskId])

  const selectedTask = useMemo(() => {
    const items = tasksQuery.data?.items ?? []
    return items.find((task) => task.taskId === selectedTaskId) ?? null
  }, [tasksQuery.data, selectedTaskId])
  const shouldPoll = selectedTask?.status === "RUNNING" || selectedTask?.status === "PENDING"

  const stagesQuery = useQuery<StageSummary[]>({
    queryKey: ["task", selectedTaskId, "stages"],
    queryFn: () => getTaskStages(selectedTaskId!),
    enabled: !!selectedTaskId,
    refetchInterval: shouldPoll ? 5000 : false,
  })

  const artifactsQuery = useQuery<ArtifactSummary[]>({
    queryKey: ["task", selectedTaskId, "artifacts"],
    queryFn: () => getTaskArtifacts(selectedTaskId!),
    enabled: !!selectedTaskId,
    refetchInterval: shouldPoll ? 5000 : false,
  })

  const findingsQuery = useQuery<PageResponse<FindingSummary>>({
    queryKey: ["task", selectedTaskId, "findings", findingPage],
    queryFn: () => getTaskFindings(selectedTaskId!, findingPage),
    enabled: !!selectedTaskId,
    refetchInterval: shouldPoll ? 7000 : false,
  })

  const handleTaskDeleted = async (taskId: string) => {
    setSelectedTaskId((current) => (current === taskId ? null : current))
    setFindingPage((prev) => ({ ...prev, offset: 0 }))
    await tasksQuery.refetch()
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("tasks.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("tasks.subtitle")}</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>{t("tasks.actions.create")}</Button>
      </div>
      <TaskCreateDialog
        open={createOpen}
        initialType={createInitialType}
        onOpenChange={(nextOpen) => {
          setCreateOpen(nextOpen)
          if (!nextOpen) {
            setCreateInitialType(undefined)
          }
        }}
        onCreated={(task) => {
          setSelectedTaskId(task.taskId)
          void tasksQuery.refetch()
        }}
      />
      <div className="grid gap-4 lg:grid-cols-[360px,1fr]">
        <TaskListPanel
          tasksQuery={tasksQuery}
          filters={filters}
          onFiltersChange={setFilters}
          selectedTaskId={selectedTaskId}
          onSelectTask={setSelectedTaskId}
        />
      {selectedTask ? (
        <TaskDetails
          task={selectedTask}
          stagesQuery={stagesQuery}
          artifactsQuery={artifactsQuery}
          findingsQuery={findingsQuery}
          findingPage={findingPage}
          onFindingPageChange={setFindingPage}
          onTaskDeleted={handleTaskDeleted}
          onAssignExecutor={(task) => setAssignState({ open: true, task })}
        />
        ) : (
          <Card className="flex items-center justify-center text-sm text-muted-foreground">
            <CardContent>{t("tasks.errors.task")}</CardContent>
          </Card>
        )}
      </div>
      <TaskAssignDialog
        open={assignState.open}
        task={assignState.task}
        onOpenChange={(open) =>
          setAssignState((prev) => ({
            ...prev,
            open,
            task: open ? prev.task : null,
          }))
        }
        onAssigned={(updated) => {
          setAssignState({ open: false, task: null })
          setSelectedTaskId(updated.taskId)
          void tasksQuery.refetch()
        }}
      />
    </div>
  )
}

type DetailsProps = {
  task: TaskSummary
  stagesQuery: UseQueryResult<StageSummary[]>
  artifactsQuery: UseQueryResult<ArtifactSummary[]>
  findingsQuery: UseQueryResult<PageResponse<FindingSummary>>
  findingPage: Pick<FindingListFilters, "limit" | "offset">
  onFindingPageChange: (page: Pick<FindingListFilters, "limit" | "offset">) => void
  onAssignExecutor: (task: TaskSummary) => void
  onTaskDeleted: (taskId: string) => Promise<void> | void
}

function TaskDetails({
  task,
  stagesQuery,
  artifactsQuery,
  findingsQuery,
  findingPage,
  onAssignExecutor,
  onFindingPageChange,
  onTaskDeleted,
}: DetailsProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const projectsQuery = useProjectsQuery()
  const executorsQuery = useExecutorsQuery()
  const [selectedStageId, setSelectedStageId] = useState<string | null>(null)
  const translateStageType = (value: string) => t(`stageTypes.${value}`, { defaultValue: value })
  const [resultReviewDialog, setResultReviewDialog] = useState<{ open: boolean; stage: StageSummary | null }>({
    open: false,
    stage: null,
  })
  const [resultReviewForm, setResultReviewForm] = useState<{
    aiReviewEnabled: boolean
    aiReviewMode: "simple" | "precise"
    aiReviewDataFlowMode: "simple" | "precise"
  }>({
    aiReviewEnabled: task.spec.aiReview?.enabled ?? false,
    aiReviewMode: task.spec.aiReview?.mode ?? "simple",
    aiReviewDataFlowMode: task.spec.aiReview?.dataFlowMode ?? "precise",
  })
  const [resultReviewError, setResultReviewError] = useState<string | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [isDeletingTask, setIsDeletingTask] = useState(false)
  const findings = findingsQuery.data?.items ?? []
  const findingsTotal = findingsQuery.data?.total ?? 0
  const findingsLimit = findingPage.limit ?? 10
  const findingsOffset = findingPage.offset ?? 0
  const findingsPageIndex = findingsTotal === 0 ? 0 : Math.floor(findingsOffset / Math.max(1, findingsLimit))
  const findingsPageCount = Math.max(1, Math.ceil(Math.max(1, findingsTotal) / Math.max(1, findingsLimit)))
  const canPrevFindings = findingsOffset > 0
  const canNextFindings = findingsOffset + findingsLimit < findingsTotal

  const changeFindingPage = (direction: "prev" | "next") => {
    if (direction === "prev" && !canPrevFindings) return
    if (direction === "next" && !canNextFindings) return
    const nextOffset =
      direction === "prev"
        ? Math.max(0, findingsOffset - findingsLimit)
        : Math.min(findingsTotal, findingsOffset + findingsLimit)
    onFindingPageChange({
      limit: findingsLimit,
      offset: nextOffset,
    })
  }

  const handleDeleteTask = async () => {
    if (!window.confirm(t("tasks.actions.confirmDelete", { task: task.name ?? t("common.notAvailable") }))) {
      return
    }
    setIsDeletingTask(true)
    setDeleteError(null)
    try {
      await deleteTask(task.taskId)
      await onTaskDeleted(task.taskId)
    } catch (err) {
      const message = err instanceof Error ? err.message : t("common.error", { defaultValue: "Request failed" })
      setDeleteError(typeof message === "string" ? message : String(message))
    } finally {
      setIsDeletingTask(false)
    }
  }

  useEffect(() => {
    if (!selectedStageId && stagesQuery.data && stagesQuery.data.length > 0) {
      setSelectedStageId(stagesQuery.data[0].stageId)
    }
  }, [selectedStageId, stagesQuery.data])

  const selectedStage =
    selectedStageId && stagesQuery.data
      ? stagesQuery.data.find((stage) => stage.stageId === selectedStageId) ?? null
      : null

  const stageLogsQuery = useQuery({
    queryKey: ["task", task.taskId, "stageLogs", selectedStageId],
    queryFn: () => getStageLogs(task.taskId, selectedStageId!, 500),
    enabled: Boolean(selectedStageId),
    refetchInterval: selectedStage?.status === "RUNNING" ? 5000 : false,
  })

  const runResultReviewMutation = useMutation<StageSummary | undefined, unknown, { stageId: string; request: ResultReviewRequest }>({
    mutationFn: ({ stageId, request }) => runResultReviewStage(task.taskId, stageId, request),
    onSuccess: () => {
      setResultReviewDialog({ open: false, stage: null })
      void stagesQuery.refetch()
    },
    onError: (err) => {
      const message = err instanceof Error ? err.message : t("common.error", { defaultValue: "Request failed" })
      setResultReviewError(typeof message === "string" ? message : String(message))
    },
  })

  const projectName = useMemo(() => {
    return projectsQuery.data?.find((p) => p.projectId === task.projectId)?.name ?? null
  }, [projectsQuery.data, task.projectId])

  const executorSummary = useMemo(() => {
    if (!task.executorId) return null
    return executorsQuery.data?.find((e) => e.executorId === task.executorId) ?? null
  }, [executorsQuery.data, task.executorId])

  const taskTypeLabel = t(`taskTypes.${task.type}`, { defaultValue: task.type })
  const projectLabel =
    projectName ?? (projectsQuery.isLoading ? t("common.loading") : t("common.notAvailable"))
  const executorLabel = useMemo(() => {
    if (!task.executorId) return t("tasks.details.executorUnassigned")
    if (!executorSummary) {
      return executorsQuery.isLoading ? t("common.loading") : t("common.notAvailable")
    }

    const cpu = formatPercent(executorSummary.cpuUsage)
    const totalGb = formatGbFromMb(executorSummary.memoryCapacityMb)
    const memory =
      executorSummary.memoryUsageMb === null || executorSummary.memoryUsageMb === undefined
        ? `${totalGb} GB`
        : `${formatGbFromMb(executorSummary.memoryUsageMb)} / ${totalGb} GB (${formatPercent(
            (executorSummary.memoryUsageMb / executorSummary.memoryCapacityMb) * 100
          )})`
    return (
      <div className="space-y-1">
        <div className="break-all">{executorSummary.name}</div>
        <div className="text-xs font-normal text-muted-foreground">
          {t("executors.table.cpu")}: {cpu} · {t("executors.table.memory")}: {memory}
        </div>
      </div>
    )
  }, [executorSummary, executorsQuery.isLoading, task.executorId, t])

  const summaryItems: DetailItem[] = [
    { label: t("tasks.details.status"), value: <StatusBadge status={task.status} /> },
    { label: t("tasks.details.type"), value: taskTypeLabel },
    { label: t("tasks.details.project"), value: projectLabel },
    { label: t("tasks.details.name"), value: task.name ?? "—" },
    { label: t("tasks.details.owner"), value: task.owner ?? "—" },
    { label: t("tasks.details.executor"), value: executorLabel },
    { label: t("tasks.details.sourceRefType"), value: task.sourceRefType },
    { label: t("tasks.details.sourceRef"), value: task.sourceRef ?? "—" },
    { label: t("tasks.details.commitSha"), value: task.commitSha ?? "—" },
    {
      label: t("tasks.details.semgrepPro"),
      value: task.semgrepProEnabled ? t("common.enabled") : t("common.disabled"),
    },
    { label: t("tasks.details.createdAt"), value: formatDateTime(task.createdAt) },
    { label: t("tasks.details.updatedAt"), value: formatDateTime(task.updatedAt) },
  ]

  const sourceItems: DetailItem[] = []
  const git = task.spec.source.git
  if (git) {
    sourceItems.push({ label: t("tasks.spec.source.gitRepo"), value: git.repo })
    sourceItems.push({ label: t("tasks.spec.source.gitRef"), value: git.ref })
  }
  const archive = task.spec.source.archive
  if (archive?.uploadId) {
    sourceItems.push({ label: t("tasks.spec.source.archiveKey"), value: archive.uploadId })
  }
  if (archive?.url) {
    sourceItems.push({ label: t("tasks.spec.source.archiveUrl"), value: archive.url })
  }
  if (task.spec.source.url?.url) {
    sourceItems.push({ label: t("tasks.spec.source.url"), value: task.spec.source.url.url })
  }
  if (task.spec.source.baselineFingerprints?.length) {
    sourceItems.push({
      label: t("tasks.spec.source.baseline"),
      value: task.spec.source.baselineFingerprints.join(", "),
    })
  }

  const ruleItems: DetailItem[] = [
    { label: t("tasks.spec.ruleSelector.mode"), value: task.spec.ruleSelector.mode },
  ]
  if (task.spec.ruleSelector.profile) {
    ruleItems.push({ label: t("tasks.spec.ruleSelector.profile"), value: task.spec.ruleSelector.profile })
  }
  if (task.spec.ruleSelector.explicitRules?.length) {
    ruleItems.push({
      label: t("tasks.spec.ruleSelector.explicit"),
      value: task.spec.ruleSelector.explicitRules.join(", "),
    })
  }

  const policyItems: DetailItem[] = []
  if (task.spec.policy?.failOn?.severity) {
    policyItems.push({ label: t("tasks.spec.policy.failOn"), value: task.spec.policy.failOn.severity })
  }
  if (task.spec.policy?.scanPolicy?.mode) {
    policyItems.push({ label: t("tasks.spec.policy.scanMode"), value: task.spec.policy.scanPolicy.mode })
  }
  if (task.spec.policy?.scanPolicy?.parallelism !== undefined) {
    policyItems.push({
      label: t("tasks.spec.policy.parallelism"),
      value: String(task.spec.policy.scanPolicy.parallelism),
    })
  }
  if (task.spec.policy?.scanPolicy?.failStrategy) {
    policyItems.push({
      label: t("tasks.spec.policy.failStrategy"),
      value: task.spec.policy.scanPolicy.failStrategy,
    })
  }

  const ticketItems: DetailItem[] = []
  if (task.spec.ticket) {
    ticketItems.push({ label: t("tasks.spec.ticket.project"), value: task.spec.ticket.project })
    ticketItems.push({ label: t("tasks.spec.ticket.assignee"), value: task.spec.ticket.assigneeStrategy })
    if (task.spec.ticket.slaDays !== undefined && task.spec.ticket.slaDays !== null) {
      ticketItems.push({ label: t("tasks.spec.ticket.sla"), value: String(task.spec.ticket.slaDays) })
    }
    if (task.spec.ticket.labels?.length) {
      ticketItems.push({
        label: t("tasks.spec.ticket.labels"),
        value: task.spec.ticket.labels.join(", "),
      })
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{task.name ?? t("common.notAvailable")}</CardTitle>
          <p className="text-sm text-muted-foreground">{projectLabel}</p>
        </CardHeader>
        <CardContent>
          <DetailGrid items={summaryItems} />
          <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
            <div className="min-h-[1.25rem] flex-1 text-sm text-destructive">{deleteError}</div>
            <div className="flex gap-2">
              <Button type="button" variant="destructive" size="sm" onClick={handleDeleteTask} disabled={isDeletingTask}>
                {isDeletingTask ? t("common.loading") : t("tasks.actions.delete")}
              </Button>
              <Button size="sm" variant="outline" onClick={() => onAssignExecutor(task)}>
                {task.executorId ? t("tasks.actions.reassignExecutor") : t("tasks.actions.assignExecutor")}
              </Button>
            </div>
          </div>
        </CardContent>
      </Card>
      {sourceItems.length > 0 || ruleItems.length > 0 || policyItems.length > 0 || ticketItems.length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t("tasks.spec.title")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-6">
            {sourceItems.length > 0 ? (
              <SpecSection title={t("tasks.spec.source.title")} items={sourceItems} />
            ) : null}
            {ruleItems.length > 0 ? (
              <SpecSection title={t("tasks.spec.ruleSelector.title")} items={ruleItems} />
            ) : null}
            {policyItems.length > 0 ? (
              <SpecSection title={t("tasks.spec.policy.title")} items={policyItems} />
            ) : null}
            {ticketItems.length > 0 ? (
              <SpecSection title={t("tasks.spec.ticket.title")} items={ticketItems} />
            ) : null}
          </CardContent>
        </Card>
      ) : null}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("tasks.stages.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          {stagesQuery.isLoading ? (
            <Spinner size={18} className="text-muted-foreground" />
          ) : stagesQuery.isError ? (
            <p className="text-sm text-destructive">{t("tasks.stages.error")}</p>
          ) : stagesQuery.data && stagesQuery.data.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="py-2 text-left">{t("tasks.stages.columns.id")}</th>
                    <th className="py-2 text-left">{t("tasks.stages.columns.type")}</th>
                    <th className="py-2 text-left">{t("tasks.stages.columns.status")}</th>
                    <th className="py-2 text-left">{t("tasks.stages.columns.artifacts")}</th>
                    <th className="py-2 text-left">{t("tasks.stages.columns.started")}</th>
                    <th className="py-2 text-left">{t("tasks.stages.columns.ended")}</th>
                    <th className="py-2 text-right">{t("tasks.stages.columns.actions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {stagesQuery.data.map((stage) => (
                    <tr
                      key={stage.stageId}
                      className={`border-b last:border-b-0 transition-colors ${
                        selectedStageId === stage.stageId ? "bg-muted/50" : ""
                      }`}
                    >
                      <td className="py-2 font-mono text-xs">{stage.stageId}</td>
                      <td className="py-2">{translateStageType(stage.type)}</td>
                      <td className="py-2">
                        <StatusBadge status={stage.status} />
                      </td>
                      <td className="py-2 text-center">{stage.artifacts.length}</td>
                      <td className="py-2 text-muted-foreground">{formatDateTime(stage.startedAt)}</td>
                      <td className="py-2 text-muted-foreground">{formatDateTime(stage.endedAt)}</td>
                      <td className="py-2 text-right">
                        <div className="flex justify-end gap-2">
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => setSelectedStageId(stage.stageId)}
                          >
                            {t("tasks.stages.actions.viewLogs")}
                          </Button>
                          {stage.type === "RESULT_REVIEW" ? (
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              onClick={() => {
                                setResultReviewError(null)
                                setResultReviewForm({
                                  aiReviewEnabled: task.spec.aiReview?.enabled ?? false,
                                  aiReviewMode: task.spec.aiReview?.mode ?? "simple",
                                  aiReviewDataFlowMode: task.spec.aiReview?.dataFlowMode ?? "precise",
                                })
                                setResultReviewDialog({ open: true, stage })
                              }}
                            >
                              {stage.status === "SUCCEEDED"
                                ? t("tasks.stages.actions.rerunResultReview")
                                : t("tasks.stages.actions.runResultReview")}
                            </Button>
                          ) : null}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("tasks.stages.empty")}</p>
          )}
        </CardContent>
      </Card>
      <StageLogsCard
        stage={selectedStage}
        logsQuery={stageLogsQuery}
        onReload={() => stageLogsQuery.refetch()}
      />

      <Dialog
        open={resultReviewDialog.open}
        onOpenChange={(open) => setResultReviewDialog((prev) => ({ ...prev, open, stage: open ? prev.stage : null }))}
      >
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>{t("tasks.stages.resultReviewDialog.title")}</DialogTitle>
            <DialogDescription>{t("tasks.stages.resultReviewDialog.subtitle")}</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            {resultReviewDialog.stage ? (
              <p className="text-xs text-muted-foreground">
                {t("tasks.stages.resultReviewDialog.stageId")}:{" "}
                <span className="font-mono">{resultReviewDialog.stage.stageId}</span>
              </p>
            ) : null}
            <div className="rounded-md border border-border p-3">
              <div className="flex items-center justify-between">
                <label className="text-sm font-semibold">{t("tasks.stages.resultReviewDialog.aiReview.label")}</label>
                <Checkbox
                  checked={resultReviewForm.aiReviewEnabled}
                  onChange={(event) =>
                    setResultReviewForm((prev) => ({
                      ...prev,
                      aiReviewEnabled: event.target.checked,
                    }))
                  }
                  aria-label={t("tasks.stages.resultReviewDialog.aiReview.label")}
                />
              </div>
              <p className="mt-1 text-xs text-muted-foreground">{t("tasks.stages.resultReviewDialog.aiReview.description")}</p>
              {resultReviewForm.aiReviewEnabled ? (
                <div className="mt-2">
                  <label className="text-xs font-medium text-muted-foreground">
                    {t("tasks.stages.resultReviewDialog.aiReview.mode")}
                  </label>
                  <select
                    className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                    value={resultReviewForm.aiReviewMode}
                    onChange={(event) =>
                      setResultReviewForm((prev) => ({
                        ...prev,
                        aiReviewMode: event.target.value as "simple" | "precise",
                      }))
                    }
                  >
                    <option value="simple">{t("tasks.createDialog.aiReview.modes.simple")}</option>
                    <option value="precise">{t("tasks.createDialog.aiReview.modes.precise")}</option>
                  </select>
                  <div className="mt-2">
                    <label className="text-xs font-medium text-muted-foreground">
                      {t("tasks.stages.resultReviewDialog.aiReview.dataFlowMode")}
                    </label>
                    <select
                      className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                      value={resultReviewForm.aiReviewDataFlowMode}
                      onChange={(event) =>
                        setResultReviewForm((prev) => ({
                          ...prev,
                          aiReviewDataFlowMode: event.target.value as "simple" | "precise",
                        }))
                      }
                    >
                      <option value="precise">{t("tasks.createDialog.aiReview.modes.precise")}</option>
                      <option value="simple">{t("tasks.createDialog.aiReview.modes.simple")}</option>
                    </select>
                  </div>
                </div>
              ) : null}
            </div>
            {resultReviewError ? <p className="text-sm text-destructive">{resultReviewError}</p> : null}
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setResultReviewDialog({ open: false, stage: null })}
              disabled={runResultReviewMutation.isPending}
            >
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              onClick={() => {
                const stageId = resultReviewDialog.stage?.stageId
                if (!stageId) return
                if (
                  resultReviewForm.aiReviewEnabled &&
                  (!resultReviewForm.aiReviewMode || !resultReviewForm.aiReviewDataFlowMode)
                ) {
                  setResultReviewError(t("tasks.createDialog.errors.aiReviewMode"))
                  return
                }
                setResultReviewError(null)
                runResultReviewMutation.mutate({
                  stageId,
                  request: {
                    autoTicket: false,
                    aiReviewEnabled: resultReviewForm.aiReviewEnabled,
                    aiReviewMode: resultReviewForm.aiReviewEnabled ? resultReviewForm.aiReviewMode : null,
                    aiReviewDataFlowMode: resultReviewForm.aiReviewEnabled ? resultReviewForm.aiReviewDataFlowMode : null,
                  },
                })
              }}
              disabled={runResultReviewMutation.isPending}
            >
              {runResultReviewMutation.isPending ? t("common.loading") : t("tasks.stages.resultReviewDialog.submit")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("tasks.artifacts.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          {artifactsQuery.isLoading ? (
            <Spinner size={18} className="text-muted-foreground" />
          ) : artifactsQuery.isError ? (
            <p className="text-sm text-destructive">{t("tasks.artifacts.error")}</p>
          ) : artifactsQuery.data && artifactsQuery.data.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="py-2 text-left">{t("tasks.artifacts.columns.id")}</th>
                    <th className="py-2 text-left">{t("tasks.artifacts.columns.stage")}</th>
                    <th className="py-2 text-left">{t("tasks.artifacts.columns.uri")}</th>
                    <th className="py-2 text-left">{t("tasks.artifacts.columns.kind")}</th>
                    <th className="py-2 text-left">{t("tasks.artifacts.columns.size")}</th>
                    <th className="py-2 text-left">{t("tasks.artifacts.columns.checksum")}</th>
                  </tr>
                </thead>
                <tbody>
                  {artifactsQuery.data.map((artifact) => (
                    <tr key={artifact.artifactId} className="border-b last:border-b-0">
                      <td className="py-2 font-mono text-xs">{artifact.artifactId}</td>
                      <td className="py-2 font-mono text-xs">{artifact.stageId}</td>
                      <td className="py-2 break-all">{artifact.uri}</td>
                      <td className="py-2">{artifact.kind}</td>
                      <td className="py-2 text-muted-foreground">
                        {artifact.sizeBytes ? formatBytes(artifact.sizeBytes) : "—"}
                      </td>
                      <td className="py-2 font-mono text-xs break-all">{artifact.checksum ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("tasks.artifacts.empty")}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("tasks.findings.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          {findingsQuery.isLoading ? (
            <Spinner size={18} className="text-muted-foreground" />
          ) : findingsQuery.isError ? (
            <p className="text-sm text-destructive">{t("tasks.findings.error")}</p>
          ) : findings.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="min-w-full table-fixed text-sm">
                <colgroup>
                  <col className="w-[140px]" />
                  <col className="w-[320px]" />
                  <col className="w-[140px]" />
                  <col className="w-[220px]" />
                </colgroup>
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="py-2 text-left">{t("tasks.findings.columns.severity")}</th>
                    <th className="py-2 text-left">{t("tasks.findings.columns.rule")}</th>
                    <th className="py-2 text-left">{t("tasks.findings.columns.status")}</th>
                    <th className="py-2 text-left">{t("tasks.findings.columns.introducedBy")}</th>
                  </tr>
                </thead>
                <tbody>
                  {findings.map((finding) => (
                    <tr key={finding.findingId} className="border-b last:border-b-0">
                      <td className="py-2 align-top">
                        <SeverityBadge severity={finding.severity} />
                      </td>
                      <td className="py-2 align-top">
                        <button
                          type="button"
                          className="block max-w-[320px] truncate text-left text-primary underline-offset-2 hover:underline"
                          title={finding.ruleId ?? "—"}
                          onClick={() => navigate(`/findings/${finding.findingId}`)}
                        >
                          {finding.ruleId ?? t("findings.untitledRule", { defaultValue: "Untitled rule" })}
                        </button>
                      </td>
                      <td className="py-2 align-top">
                        <StatusBadge status={finding.status} />
                      </td>
                      <td className="py-2 align-top">
                        <span className="block max-w-[220px] truncate" title={finding.introducedBy ?? "—"}>
                          {finding.introducedBy ?? "—"}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="mt-3 flex items-center justify-between text-xs text-muted-foreground">
                <span>
                  {t("common.pageStatus", {
                    page: findingsTotal === 0 ? 0 : findingsPageIndex + 1,
                    total: findingsPageCount,
                  })}
                </span>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => changeFindingPage("prev")}
                    disabled={!canPrevFindings || findingsQuery.isFetching}
                  >
                    {t("common.previous")}
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => changeFindingPage("next")}
                    disabled={!canNextFindings || findingsQuery.isFetching}
                  >
                    {t("common.next")}
                  </Button>
                </div>
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("tasks.findings.empty")}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

type TaskCreateDialogProps = {
  open: boolean
  initialType?: TaskType
  onOpenChange: (open: boolean) => void
  onCreated: (task: TaskSummary) => void
}

type TaskAssignDialogProps = {
  open: boolean
  task: TaskSummary | null
  onOpenChange: (open: boolean) => void
  onAssigned: (task: TaskSummary) => void
}

function TaskAssignDialog({ open, task, onOpenChange, onAssigned }: TaskAssignDialogProps) {
  const { t } = useTranslation()
  const executorsQuery = useExecutorsQuery({ status: "READY" })
  const [selectedExecutor, setSelectedExecutor] = useState("")
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) {
      setSelectedExecutor("")
      setError(null)
      return
    }
    setSelectedExecutor(task?.executorId ?? "")
  }, [open, task])

  const mutation = useMutation({
    mutationFn: (executorId: string) => assignTask(task!.taskId, { executorId }),
    onSuccess: (updated) => {
      onAssigned(updated)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!task) return
    if (!selectedExecutor) {
      setError(t("tasks.assignDialog.errors.executor"))
      return
    }
    setError(null)
    mutation.mutate(selectedExecutor)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("tasks.assignDialog.title")}</DialogTitle>
          <DialogDescription>{t("tasks.assignDialog.subtitle")}</DialogDescription>
        </DialogHeader>
        {task ? (
          <form className="space-y-4" onSubmit={handleSubmit}>
            <div>
              <p className="text-xs text-muted-foreground">{t("tasks.assignDialog.taskLabel")}</p>
              <p className="font-mono text-sm">{task.taskId}</p>
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tasks.assignDialog.executor")}</label>
              {executorsQuery.isLoading ? (
                <div className="mt-1 flex h-10 items-center gap-2 text-sm text-muted-foreground">
                  <Spinner size={16} /> {t("common.loading")}
                </div>
              ) : (
                <select
                  className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                  value={selectedExecutor}
                  onChange={(event) => setSelectedExecutor(event.target.value)}
                >
                  <option value="">{t("common.selectPlaceholder")}</option>
                  {executorsQuery.data?.map((executor) => (
                    <option key={executor.executorId} value={executor.executorId}>
                      {executor.name} ({executor.status})
                    </option>
                  ))}
                </select>
              )}
            </div>
            {error ? <p className="text-sm text-destructive">{error}</p> : null}
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                {t("common.cancel")}
              </Button>
              <Button type="submit" disabled={mutation.isPending || executorsQuery.isLoading || !task}>
                {mutation.isPending ? t("common.loading") : t("tasks.assignDialog.submit")}
              </Button>
            </DialogFooter>
          </form>
        ) : (
          <p className="text-sm text-muted-foreground">{t("tasks.assignDialog.noTask")}</p>
        )}
      </DialogContent>
    </Dialog>
  )
}

function TaskCreateDialog({ open, initialType, onOpenChange, onCreated }: TaskCreateDialogProps) {
  const { t } = useTranslation()
  const projectsQuery = useProjectsQuery()
  const executorsQuery = useExecutorsQuery({ status: "READY" })
  const [repositories, setRepositories] = useState<RepositoryResponse[]>([])
  const [repoLoading, setRepoLoading] = useState(false)
  const [gitMeta, setGitMeta] = useState<RepositoryGitMetadata | null>(null)
  const [gitLoading, setGitLoading] = useState(false)
  const [gitError, setGitError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [scaArchiveFile, setScaArchiveFile] = useState<File | null>(null)
  const [scaSbomFile, setScaSbomFile] = useState<File | null>(null)
  const [scaArchiveUpload, setScaArchiveUpload] = useState<{ uploadId: string; fileName: string } | null>(null)
  const [scaSbomUpload, setScaSbomUpload] = useState<{ uploadId: string; fileName: string } | null>(null)
  const [form, setForm] = useState({
    projectId: "",
    repoId: "",
    type: "CODE_CHECK" as TaskType,
    engine: "semgrep",
    executorId: "",
    name: "",
    owner: "",
    refMode: "latest" as RefMode,
    branch: "",
    tag: "",
    commit: "",
    semgrepUsePro: false,
    semgrepToken: "",
    aiReviewEnabled: false,
    aiReviewMode: "simple" as "simple" | "precise",
    aiReviewDataFlowMode: "precise" as "simple" | "precise",
    scaAiReviewEnabled: false,
    scaAiReviewCritical: true,
    scaAiReviewHigh: true,
    scaAiReviewMedium: false,
    scaAiReviewLow: false,
    scaAiReviewInfo: false,
    scaTargetType: "git" as ScaTargetType,
    scaFilesystemPath: "",
    scaImageRef: "",
  })

  const resetForm = () => {
    setForm({
      projectId: "",
      repoId: "",
      type: initialType ?? "CODE_CHECK",
      engine: (ENGINE_BINDINGS[initialType ?? "CODE_CHECK"] ?? [])[0] ?? "semgrep",
      executorId: "",
      name: "",
      owner: "",
      refMode: "latest",
      branch: "",
      tag: "",
      commit: "",
      semgrepUsePro: false,
      semgrepToken: "",
      aiReviewEnabled: false,
      aiReviewMode: "simple",
      aiReviewDataFlowMode: "precise",
      scaAiReviewEnabled: false,
      scaAiReviewCritical: true,
      scaAiReviewHigh: true,
      scaAiReviewMedium: false,
      scaAiReviewLow: false,
      scaAiReviewInfo: false,
      scaTargetType: "git",
      scaFilesystemPath: "",
      scaImageRef: "",
    })
    setRepositories([])
    setGitMeta(null)
    setGitError(null)
    setError(null)
    setScaArchiveFile(null)
    setScaSbomFile(null)
    setScaArchiveUpload(null)
    setScaSbomUpload(null)
  }

  useEffect(() => {
    if (!open) {
      resetForm()
      return
    }
    if (!form.projectId && projectsQuery.data && projectsQuery.data.length > 0) {
      const nextProject = projectsQuery.data[0]
      setForm((prev) => ({ ...prev, projectId: nextProject.projectId }))
      if (form.type !== "SCA_CHECK" || form.scaTargetType === "git") {
        void loadRepositories(nextProject.projectId)
      }
    }
  }, [open, projectsQuery.data])

  useEffect(() => {
    if (!open || !initialType || form.type === initialType) return
    const engines = ENGINE_BINDINGS[initialType] ?? []
    setForm((prev) => ({
      ...prev,
      type: initialType,
      engine: engines.includes(prev.engine) ? prev.engine : engines[0] ?? prev.engine,
    }))
  }, [open, initialType, form.type])

  useEffect(() => {
    const engines = ENGINE_BINDINGS[form.type] ?? []
    if (engines.length > 0 && !engines.includes(form.engine)) {
      setForm((prev) => ({ ...prev, engine: engines[0] }))
    }
  }, [form.type])

  useEffect(() => {
    if (!form.projectId) {
      setRepositories([])
      setForm((prev) => ({ ...prev, repoId: "" }))
      return
    }
    if (form.type === "SCA_CHECK" && form.scaTargetType !== "git") {
      return
    }
    void loadRepositories(form.projectId)
  }, [form.projectId, form.type, form.scaTargetType])

  useEffect(() => {
    if (!form.projectId || !form.repoId) {
      setGitMeta(null)
      return
    }
    if (form.type === "SCA_CHECK" && form.scaTargetType !== "git") {
      setGitMeta(null)
      return
    }
    void fetchGitMeta(form.projectId, form.repoId, false)
  }, [form.projectId, form.repoId, form.type, form.scaTargetType])

  const buildRefSelection = (): { ok: true; data: RefSelection } | { ok: false; error: string } => {
    if (form.refMode === "latest") {
      if (!gitMeta?.defaultBranch) {
        return { ok: false, error: t("tasks.createDialog.errors.noDefaultBranch") }
      }
      return {
        ok: true,
        data: {
          sourceRefType: "BRANCH",
          sourceRef: gitMeta.defaultBranch,
          commitSha: gitMeta.headCommit?.commitId,
          gitRef: gitMeta.defaultBranch,
        },
      }
    }
    if (form.refMode === "branch") {
      if (!form.branch) {
        return { ok: false, error: t("tasks.createDialog.errors.branch") }
      }
      return {
        ok: true,
        data: {
          sourceRefType: "BRANCH",
          sourceRef: form.branch,
          commitSha: undefined,
          gitRef: form.branch,
        },
      }
    }
    if (form.refMode === "tag") {
      if (!form.tag) {
        return { ok: false, error: t("tasks.createDialog.errors.tag") }
      }
      return {
        ok: true,
        data: {
          sourceRefType: "TAG",
          sourceRef: form.tag,
          commitSha: undefined,
          gitRef: form.tag,
        },
      }
    }
    if (form.refMode === "commit") {
      if (!form.commit) {
        return { ok: false, error: t("tasks.createDialog.errors.commit") }
      }
      return {
        ok: true,
        data: {
          sourceRefType: "COMMIT",
          sourceRef: form.commit,
          commitSha: form.commit,
          gitRef: form.commit,
        },
      }
    }
    return { ok: false, error: t("tasks.createDialog.errors.gitUnknown") }
  }

  const buildSpecPayload = (selection: RefSelection): TaskSpecPayload => {
    const repo = repositories.find((repo) => repo.repoId === form.repoId)
    const engineOptions =
      form.engine === "semgrep"
        ? {
            semgrep: {
              usePro: form.semgrepUsePro,
              token: form.semgrepUsePro ? form.semgrepToken.trim() : undefined,
            },
          }
        : undefined
    return {
      source: {
        git: {
          repo: repo?.remoteUrl ?? "",
          ref: selection.gitRef ?? selection.sourceRef ?? "",
          refType: selection.sourceRefType,
        },
        gitRefType: selection.sourceRefType,
      },
      ruleSelector: {
        mode: "AUTO",
      },
      engineOptions,
      aiReview: {
        enabled: form.aiReviewEnabled,
        mode: form.aiReviewMode,
        dataFlowMode: form.aiReviewDataFlowMode,
      },
    }
  }

  const usesGitSource = form.type === "CODE_CHECK" || (form.type === "SCA_CHECK" && form.scaTargetType === "git")

  const buildScaSpecPayload = (
    selection?: RefSelection
  ): { repoId?: string; sourceRefType: SourceRefType; sourceRef?: string; commitSha?: string; spec: TaskSpecPayload } => {
    const scaAiReview = {
      enabled: form.scaAiReviewEnabled,
      critical: form.scaAiReviewCritical,
      high: form.scaAiReviewHigh,
      medium: form.scaAiReviewMedium,
      low: form.scaAiReviewLow,
      info: form.scaAiReviewInfo,
    }
    if (form.scaTargetType === "git") {
      const repo = repositories.find((r) => r.repoId === form.repoId)
      return {
        repoId: form.repoId,
        sourceRefType: selection?.sourceRefType ?? "BRANCH",
        sourceRef: selection?.sourceRef,
        commitSha: selection?.commitSha,
        spec: {
          source: {
            git: {
              repo: repo?.remoteUrl ?? "",
              ref: selection?.gitRef ?? selection?.sourceRef ?? "",
              refType: selection?.sourceRefType,
            },
            gitRefType: selection?.sourceRefType,
          },
          ruleSelector: { mode: "AUTO" },
          scaAiReview,
        },
      }
    }

    if (form.scaTargetType === "archive") {
      return {
        sourceRefType: "UPLOAD",
        sourceRef: scaArchiveUpload?.uploadId,
        spec: {
          source: { archive: { uploadId: scaArchiveUpload?.uploadId } },
          ruleSelector: { mode: "AUTO" },
          scaAiReview,
        },
      }
    }

    if (form.scaTargetType === "filesystem") {
      const path = form.scaFilesystemPath.trim()
      return {
        sourceRefType: "UPLOAD",
        sourceRef: path,
        spec: {
          source: { filesystem: { path } },
          ruleSelector: { mode: "AUTO" },
          scaAiReview,
        },
      }
    }

    if (form.scaTargetType === "image") {
      const ref = form.scaImageRef.trim()
      return {
        sourceRefType: "UPLOAD",
        sourceRef: ref,
        spec: {
          source: { image: { ref } },
          ruleSelector: { mode: "AUTO" },
          scaAiReview,
        },
      }
    }

    return {
      sourceRefType: "UPLOAD",
      sourceRef: scaSbomUpload?.uploadId,
      spec: {
        source: { sbom: { uploadId: scaSbomUpload?.uploadId } },
        ruleSelector: { mode: "AUTO" },
        scaAiReview,
      },
    }
  }

  const mutation = useMutation<TaskSummary, unknown, CreateTaskPayload>({
    mutationFn: (payload) => createTask(payload),
    onSuccess: (task) => {
      onCreated(task)
      onOpenChange(false)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const archiveUploadMutation = useMutation({
    mutationFn: (file: File) => uploadScaArchive(form.projectId, file),
    onSuccess: (data) => {
      setScaArchiveUpload({ uploadId: data.uploadId, fileName: data.fileName })
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "upload failed")
    },
  })

  const sbomUploadMutation = useMutation({
    mutationFn: (file: File) => uploadScaSbom(form.projectId, file),
    onSuccess: (data) => {
      setScaSbomUpload({ uploadId: data.uploadId, fileName: data.fileName })
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "upload failed")
    },
  })

  const loadRepositories = async (projectId: string) => {
    setRepoLoading(true)
    try {
      const data = await listRepositories(projectId)
      setRepositories(data)
      setForm((prev) => ({
        ...prev,
        repoId: prev.repoId && data.some((repo) => repo.repoId === prev.repoId) ? prev.repoId : data[0]?.repoId ?? "",
      }))
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load repositories")
    } finally {
      setRepoLoading(false)
    }
  }

  const fetchGitMeta = async (projectId: string, repoId: string, refresh: boolean) => {
    setGitLoading(true)
    setGitError(null)
    try {
      const data = await getRepositoryGitMetadata(projectId, repoId, refresh)
      setGitMeta(data ?? null)
      if (data?.defaultBranch) {
        setForm((prev) => ({
          ...prev,
          branch: data.defaultBranch ?? "",
        }))
      }
      if (data?.headCommit?.commitId) {
        setForm((prev) => ({
          ...prev,
          commit: data.headCommit?.commitId ?? "",
        }))
      }
    } catch (err) {
      setGitError(err instanceof Error ? err.message : "Failed to fetch git metadata")
    } finally {
      setGitLoading(false)
    }
  }

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    if (!form.projectId) {
      setError(t("tasks.createDialog.errors.project"))
      return
    }
    if (!form.name.trim()) {
      setError(t("tasks.createDialog.errors.name"))
      return
    }

    if (form.type === "SCA_CHECK") {
      if (
        form.scaAiReviewEnabled &&
        !(
          form.scaAiReviewCritical ||
          form.scaAiReviewHigh ||
          form.scaAiReviewMedium ||
          form.scaAiReviewLow ||
          form.scaAiReviewInfo
        )
      ) {
        setError(t("tasks.createDialog.errors.scaAiReviewSeverities"))
        return
      }
      if (form.scaTargetType === "git") {
        if (!form.repoId) {
          setError(t("sca.createDialog.errors.repository"))
          return
        }
        const refResult = buildRefSelection()
        if (!refResult.ok) {
          setError(refResult.error)
          return
        }
        const built = buildScaSpecPayload(refResult.data)
        mutation.mutate({
          projectId: form.projectId,
          repoId: built.repoId,
          executorId: form.executorId || undefined,
          type: "SCA_CHECK",
          engine: form.engine,
          name: form.name.trim(),
          sourceRefType: built.sourceRefType,
          sourceRef: built.sourceRef,
          commitSha: built.commitSha,
          owner: form.owner.trim() || undefined,
          spec: built.spec,
        })
        return
      }

      if (form.scaTargetType === "archive" && !scaArchiveUpload?.uploadId) {
        setError(t("sca.createDialog.errors.archiveUpload"))
        return
      }
      if (form.scaTargetType === "sbom" && !scaSbomUpload?.uploadId) {
        setError(t("sca.createDialog.errors.sbomUpload"))
        return
      }
      if (form.scaTargetType === "filesystem" && !form.scaFilesystemPath.trim()) {
        setError(t("sca.createDialog.errors.filesystemPath"))
        return
      }
      if (form.scaTargetType === "image" && !form.scaImageRef.trim()) {
        setError(t("sca.createDialog.errors.imageRef"))
        return
      }

      const built = buildScaSpecPayload()
      mutation.mutate({
        projectId: form.projectId,
        executorId: form.executorId || undefined,
        type: "SCA_CHECK",
        engine: form.engine,
        name: form.name.trim(),
        sourceRefType: built.sourceRefType,
        sourceRef: built.sourceRef,
        owner: form.owner.trim() || undefined,
        spec: built.spec,
      })
      return
    }

    if (!form.repoId) {
      setError(t("tasks.createDialog.errors.repository"))
      return
    }
    if (form.engine === "semgrep" && form.semgrepUsePro && !form.semgrepToken.trim()) {
      setError(t("tasks.createDialog.errors.semgrepToken"))
      return
    }
    if (form.aiReviewEnabled && (!form.aiReviewMode || !form.aiReviewDataFlowMode)) {
      setError(t("tasks.createDialog.errors.aiReviewMode"))
      return
    }
    const refResult = buildRefSelection()
    if (!refResult.ok) {
      setError(refResult.error)
      return
    }
    const spec = buildSpecPayload(refResult.data)
    mutation.mutate({
      projectId: form.projectId,
      repoId: form.repoId,
      executorId: form.executorId || undefined,
      type: form.type,
      engine: form.engine,
      name: form.name.trim(),
      sourceRefType: refResult.data.sourceRefType,
      sourceRef: refResult.data.sourceRef,
      commitSha: refResult.data.commitSha,
      owner: form.owner.trim() || undefined,
      spec,
    })
  }

  const canUploadArchive = Boolean(form.projectId && scaArchiveFile && !archiveUploadMutation.isPending)
  const canUploadSbom = Boolean(form.projectId && scaSbomFile && !sbomUploadMutation.isPending)

  const renderScaTargetFields = () => {
    if (form.type !== "SCA_CHECK" || form.scaTargetType === "git") {
      return null
    }

    if (form.scaTargetType === "archive") {
      return (
        <div className="space-y-3">
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.archive")}</label>
            <input
              type="file"
              accept=".zip"
              className="mt-2 block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border file:border-input file:bg-background file:px-3 file:py-2 file:text-sm file:font-medium"
              onChange={(event) => setScaArchiveFile(event.target.files?.[0] ?? null)}
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={!canUploadArchive}
              onClick={() => scaArchiveFile && archiveUploadMutation.mutate(scaArchiveFile)}
            >
              {archiveUploadMutation.isPending ? t("common.loading") : t("sca.createDialog.upload")}
            </Button>
            {scaArchiveUpload ? (
              <Badge variant="secondary" className="break-all">
                {scaArchiveUpload.fileName} ({scaArchiveUpload.uploadId})
              </Badge>
            ) : null}
          </div>
        </div>
      )
    }

    if (form.scaTargetType === "filesystem") {
      return (
        <div>
          <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.filesystemPath")}</label>
          <Input
            className="mt-1"
            value={form.scaFilesystemPath}
            onChange={(event) => setForm((prev) => ({ ...prev, scaFilesystemPath: event.target.value }))}
          />
          <p className="mt-1 text-xs text-muted-foreground">{t("sca.createDialog.filesystemHint")}</p>
        </div>
      )
    }

    if (form.scaTargetType === "image") {
      return (
        <div>
          <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.imageRef")}</label>
          <Input
            className="mt-1"
            value={form.scaImageRef}
            onChange={(event) => setForm((prev) => ({ ...prev, scaImageRef: event.target.value }))}
          />
          <p className="mt-1 text-xs text-muted-foreground">{t("sca.createDialog.imageHint")}</p>
        </div>
      )
    }

    return (
      <div className="space-y-3">
        <div>
          <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.sbom")}</label>
          <input
            type="file"
            accept=".json,.cdx.json"
            className="mt-2 block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border file:border-input file:bg-background file:px-3 file:py-2 file:text-sm file:font-medium"
            onChange={(event) => setScaSbomFile(event.target.files?.[0] ?? null)}
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            type="button"
            variant="outline"
            disabled={!canUploadSbom}
            onClick={() => scaSbomFile && sbomUploadMutation.mutate(scaSbomFile)}
          >
            {sbomUploadMutation.isPending ? t("common.loading") : t("sca.createDialog.upload")}
          </Button>
          {scaSbomUpload ? (
            <Badge variant="secondary" className="break-all">
              {scaSbomUpload.fileName} ({scaSbomUpload.uploadId})
            </Badge>
          ) : null}
        </div>
      </div>
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("tasks.createDialog.title")}</DialogTitle>
          <DialogDescription>{t("tasks.createDialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="space-y-4" onSubmit={submit}>
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.project")}</label>
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={form.projectId}
                onChange={(event) => setForm((prev) => ({ ...prev, projectId: event.target.value }))}
              >
                <option value="">{t("common.selectPlaceholder")}</option>
                {projectsQuery.data?.map((project) => (
                  <option key={project.projectId} value={project.projectId}>
                    {project.name}
                  </option>
                ))}
              </select>
            </div>
            {form.type === "SCA_CHECK" ? (
              <div>
                <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.targetType")}</label>
                <select
                  className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                  value={form.scaTargetType}
                  onChange={(event) =>
                    setForm((prev) => ({ ...prev, scaTargetType: event.target.value as ScaTargetType }))
                  }
                >
                  {SCA_TARGET_TYPES.map((tt) => (
                    <option key={tt} value={tt}>
                      {t(`sca.createDialog.targetTypes.${tt}`)}
                    </option>
                  ))}
                </select>
              </div>
            ) : null}
            {usesGitSource ? (
              <div>
                <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.repository")}</label>
                {repoLoading ? (
                  <div className="mt-1 flex h-10 items-center gap-2 text-sm text-muted-foreground">
                    <Spinner size={16} /> {t("common.loading")}
                  </div>
                ) : (
                  <select
                    className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                    value={form.repoId}
                    onChange={(event) => setForm((prev) => ({ ...prev, repoId: event.target.value }))}
                  >
                    <option value="">{t("common.selectPlaceholder")}</option>
                    {repositories.map((repo) => (
                      <option key={repo.repoId} value={repo.repoId}>
                        {repo.remoteUrl ?? repo.repoId}
                      </option>
                    ))}
                  </select>
                )}
              </div>
            ) : null}
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.type")}</label>
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={form.type}
                onChange={(event) => {
                  const nextType = event.target.value as TaskType
                  const allowedEngines = ENGINE_BINDINGS[nextType] ?? []
                  setForm((prev) => ({
                    ...prev,
                    type: nextType,
                    engine: allowedEngines.includes(prev.engine) ? prev.engine : allowedEngines[0] ?? "",
                    ...(allowedEngines.includes("semgrep")
                      ? {}
                      : {
                          semgrepUsePro: false,
                          semgrepToken: "",
                        }),
                  }))
                }}
              >
                {TASK_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {t(`taskTypes.${type}`, { defaultValue: type })}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.engine")}</label>
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={form.engine}
                onChange={(event) => {
                  const value = event.target.value
                  setForm((prev) => ({
                    ...prev,
                    engine: value,
                    ...(value === "semgrep"
                      ? {}
                      : {
                          semgrepUsePro: false,
                          semgrepToken: "",
                        }),
                  }))
                }}
              >
                {(ENGINE_BINDINGS[form.type] ?? []).map((engine) => (
                  <option key={engine} value={engine}>
                    {engine}
                  </option>
                ))}
              </select>
            </div>
            {form.engine === "semgrep" ? (
              <div className="rounded-md border border-dashed border-border p-3">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-semibold">{t("tasks.createDialog.semgrepPro.label")}</label>
                  <Checkbox
                    checked={form.semgrepUsePro}
                    onChange={(event) =>
                      setForm((prev) => ({
                        ...prev,
                        semgrepUsePro: event.target.checked,
                        semgrepToken: event.target.checked ? prev.semgrepToken : "",
                      }))
                    }
                    aria-label={t("tasks.createDialog.semgrepPro.label")}
                  />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{t("tasks.createDialog.semgrepPro.description")}</p>
                {form.semgrepUsePro ? (
                  <div className="mt-2">
                    <label className="text-xs font-medium text-muted-foreground">
                      {t("tasks.createDialog.semgrepPro.token")}
                    </label>
                    <Input
                      className="mt-1"
                      type="password"
                      value={form.semgrepToken}
                      onChange={(event) => setForm((prev) => ({ ...prev, semgrepToken: event.target.value }))}
                      placeholder="sgp_****************"
                    />
                  </div>
                ) : null}
              </div>
            ) : null}
            {form.type === "CODE_CHECK" ? (
              <div className="rounded-md border border-dashed border-border p-3">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-semibold">{t("tasks.createDialog.aiReview.label")}</label>
                  <Checkbox
                    checked={form.aiReviewEnabled}
                    onChange={(event) =>
                      setForm((prev) => ({
                        ...prev,
                        aiReviewEnabled: event.target.checked,
                      }))
                    }
                    aria-label={t("tasks.createDialog.aiReview.label")}
                  />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{t("tasks.createDialog.aiReview.description")}</p>
                {form.aiReviewEnabled ? (
                  <div className="mt-2">
                    <label className="text-xs font-medium text-muted-foreground">
                      {t("tasks.createDialog.aiReview.mode")}
                    </label>
                    <select
                      className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                      value={form.aiReviewMode}
                      onChange={(event) =>
                        setForm((prev) => ({
                          ...prev,
                          aiReviewMode: event.target.value as "simple" | "precise",
                        }))
                      }
                    >
                      <option value="simple">{t("tasks.createDialog.aiReview.modes.simple")}</option>
                      <option value="precise">{t("tasks.createDialog.aiReview.modes.precise")}</option>
                    </select>
                    <div className="mt-2">
                      <label className="text-xs font-medium text-muted-foreground">
                        {t("tasks.createDialog.aiReview.dataFlowMode")}
                      </label>
                      <select
                        className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                        value={form.aiReviewDataFlowMode}
                        onChange={(event) =>
                          setForm((prev) => ({
                            ...prev,
                            aiReviewDataFlowMode: event.target.value as "simple" | "precise",
                          }))
                        }
                      >
                        <option value="precise">{t("tasks.createDialog.aiReview.modes.precise")}</option>
                        <option value="simple">{t("tasks.createDialog.aiReview.modes.simple")}</option>
                      </select>
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}
            {form.type === "SCA_CHECK" ? (
              <div className="rounded-md border border-dashed border-border p-3">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-semibold">{t("tasks.createDialog.scaAiReview.label")}</label>
                  <Checkbox
                    checked={form.scaAiReviewEnabled}
                    onChange={(event) =>
                      setForm((prev) => {
                        const enabled = event.target.checked
                        const anySeverity =
                          prev.scaAiReviewCritical ||
                          prev.scaAiReviewHigh ||
                          prev.scaAiReviewMedium ||
                          prev.scaAiReviewLow ||
                          prev.scaAiReviewInfo
                        return {
                          ...prev,
                          scaAiReviewEnabled: enabled,
                          ...(enabled && !anySeverity
                            ? {
                                scaAiReviewCritical: true,
                                scaAiReviewHigh: true,
                              }
                            : {}),
                        }
                      })
                    }
                    aria-label={t("tasks.createDialog.scaAiReview.label")}
                  />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{t("tasks.createDialog.scaAiReview.description")}</p>
                {form.scaAiReviewEnabled ? (
                  <div className="mt-2 grid gap-2 md:grid-cols-2">
                    <div className="flex items-center justify-between rounded-md border border-border/50 bg-muted/20 px-3 py-2">
                      <span className="text-xs font-medium text-muted-foreground">{t("severities.CRITICAL")}</span>
                      <Checkbox
                        checked={form.scaAiReviewCritical}
                        onChange={(event) => setForm((prev) => ({ ...prev, scaAiReviewCritical: event.target.checked }))}
                        aria-label={t("severities.CRITICAL")}
                      />
                    </div>
                    <div className="flex items-center justify-between rounded-md border border-border/50 bg-muted/20 px-3 py-2">
                      <span className="text-xs font-medium text-muted-foreground">{t("severities.HIGH")}</span>
                      <Checkbox
                        checked={form.scaAiReviewHigh}
                        onChange={(event) => setForm((prev) => ({ ...prev, scaAiReviewHigh: event.target.checked }))}
                        aria-label={t("severities.HIGH")}
                      />
                    </div>
                    <div className="flex items-center justify-between rounded-md border border-border/50 bg-muted/20 px-3 py-2">
                      <span className="text-xs font-medium text-muted-foreground">{t("severities.MEDIUM")}</span>
                      <Checkbox
                        checked={form.scaAiReviewMedium}
                        onChange={(event) => setForm((prev) => ({ ...prev, scaAiReviewMedium: event.target.checked }))}
                        aria-label={t("severities.MEDIUM")}
                      />
                    </div>
                    <div className="flex items-center justify-between rounded-md border border-border/50 bg-muted/20 px-3 py-2">
                      <span className="text-xs font-medium text-muted-foreground">{t("severities.LOW")}</span>
                      <Checkbox
                        checked={form.scaAiReviewLow}
                        onChange={(event) => setForm((prev) => ({ ...prev, scaAiReviewLow: event.target.checked }))}
                        aria-label={t("severities.LOW")}
                      />
                    </div>
                    <div className="flex items-center justify-between rounded-md border border-border/50 bg-muted/20 px-3 py-2 md:col-span-2">
                      <span className="text-xs font-medium text-muted-foreground">{t("severities.INFO")}</span>
                      <Checkbox
                        checked={form.scaAiReviewInfo}
                        onChange={(event) => setForm((prev) => ({ ...prev, scaAiReviewInfo: event.target.checked }))}
                        aria-label={t("severities.INFO")}
                      />
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.executorSelect")}</label>
              {executorsQuery.isLoading ? (
                <div className="mt-1 flex h-10 items-center gap-2 text-sm text-muted-foreground">
                  <Spinner size={16} /> {t("common.loading")}
                </div>
              ) : (
                <select
                  className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                  value={form.executorId}
                  onChange={(event) => setForm((prev) => ({ ...prev, executorId: event.target.value }))}
                >
                  <option value="">{t("tasks.createDialog.executorPlaceholder")}</option>
                  {executorsQuery.data?.map((executor) => (
                    <option key={executor.executorId} value={executor.executorId}>
                      {executor.name} ({executor.status})
                    </option>
                  ))}
                </select>
              )}
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">
                {t("tasks.createDialog.name")}
              </label>
              <Input
                className="mt-1"
                value={form.name}
                onChange={(event) => setForm((prev) => ({ ...prev, name: event.target.value }))}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">
                {t("tasks.createDialog.owner")}
              </label>
              <Input
                className="mt-1"
                value={form.owner}
                onChange={(event) => setForm((prev) => ({ ...prev, owner: event.target.value }))}
              />
            </div>
          </div>
          {usesGitSource ? (
            <div className="rounded-lg border border-border p-4">
            <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
              <div>
                <p className="text-sm font-semibold">{t("tasks.createDialog.gitSection")}</p>
                <p className="text-xs text-muted-foreground">
                  {gitMeta
                    ? t("tasks.createDialog.gitSummary", {
                        branch: gitMeta.defaultBranch ?? t("common.none"),
                        commit: gitMeta.headCommit?.commitId?.slice(0, 8) ?? t("common.none"),
                      })
                    : t("tasks.createDialog.gitEmpty")}
                </p>
              </div>
              {form.projectId && form.repoId ? (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={gitLoading}
                  onClick={() => fetchGitMeta(form.projectId, form.repoId, true)}
                >
                  {gitLoading ? (
                    <span className="flex items-center gap-2">
                      <Spinner size={14} /> {t("common.loading")}
                    </span>
                  ) : (
                    t("tasks.createDialog.gitRefresh")
                  )}
                </Button>
              ) : null}
            </div>
            {gitError ? <p className="mt-2 text-sm text-destructive">{gitError}</p> : null}
            <div className="mt-4 grid gap-4 md:grid-cols-3">
              <div>
                <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.refMode")}</label>
                <select
                  className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                  value={form.refMode}
                  onChange={(event) => setForm((prev) => ({ ...prev, refMode: event.target.value as RefMode }))}
                >
                  <option value="latest">{t("tasks.createDialog.refLatest")}</option>
                  <option value="branch">{t("tasks.createDialog.refBranch")}</option>
                  <option value="tag">{t("tasks.createDialog.refTag")}</option>
                  <option value="commit">{t("tasks.createDialog.refCommit")}</option>
                </select>
              </div>
              {form.refMode === "branch" ? (
                <div>
                  <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.branch")}</label>
                  <select
                    className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                    value={form.branch}
                    onChange={(event) => setForm((prev) => ({ ...prev, branch: event.target.value }))}
                  >
                    <option value="">{t("common.selectPlaceholder")}</option>
                    {gitMeta?.branches?.map((branch) => (
                      <option key={branch.name} value={branch.name}>
                        {branch.name}
                      </option>
                    ))}
                  </select>
                </div>
              ) : null}
              {form.refMode === "tag" ? (
                <div>
                  <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.tag")}</label>
                  <select
                    className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                    value={form.tag}
                    onChange={(event) => setForm((prev) => ({ ...prev, tag: event.target.value }))}
                  >
                    <option value="">{t("common.selectPlaceholder")}</option>
                    {gitMeta?.tags?.map((tag) => (
                      <option key={tag.name} value={tag.name}>
                        {tag.name}
                      </option>
                    ))}
                  </select>
                </div>
              ) : null}
              {form.refMode === "commit" ? (
                <div>
                  <label className="text-xs font-medium text-muted-foreground">{t("tasks.createDialog.commit")}</label>
                  <select
                    className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                    value={form.commit}
                    onChange={(event) => setForm((prev) => ({ ...prev, commit: event.target.value }))}
                  >
                    <option value="">{t("common.selectPlaceholder")}</option>
                    {gitMeta?.commits?.map((commit) => (
                      <option key={commit.commitId} value={commit.commitId}>
                        {commit.shortMessage ? `${commit.commitId.slice(0, 8)} - ${commit.shortMessage}` : commit.commitId}
                      </option>
                    ))}
                  </select>
                </div>
              ) : null}
            </div>
            </div>
          ) : form.type === "SCA_CHECK" ? (
            <div className="rounded-lg border border-border p-4">{renderScaTargetFields()}</div>
          ) : null}
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t("common.loading") : t("tasks.createDialog.submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type DetailItem = { label: string; value?: ReactNode }

type RefSelection = {
  sourceRefType: SourceRefType
  sourceRef?: string
  commitSha?: string
  gitRef?: string
}

function DetailGrid({ items }: { items: DetailItem[] }) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {items.map((item) => (
        <div key={item.label}>
          <p className="text-xs uppercase text-muted-foreground">{item.label}</p>
          <div className="mt-1 break-all text-sm font-medium">{renderDetailValue(item.value)}</div>
        </div>
      ))}
    </div>
  )
}

function renderDetailValue(value?: ReactNode) {
  if (value === null || value === undefined || value === false) {
    return <span className="text-muted-foreground">—</span>
  }
  if (typeof value === "string" && value.trim().length === 0) {
    return <span className="text-muted-foreground">—</span>
  }
  return value
}

function SpecSection({ title, items }: { title: string; items: DetailItem[] }) {
  if (!items.length) {
    return null
  }
  return (
    <div>
      <p className="text-sm font-semibold">{title}</p>
      <div className="mt-3 grid gap-4 md:grid-cols-2">
        {items.map((item) => (
          <div key={item.label}>
            <p className="text-xs uppercase text-muted-foreground">{item.label}</p>
            <div className="mt-1 break-all text-sm font-mono">{renderDetailValue(item.value)}</div>
          </div>
        ))}
      </div>
    </div>
  )
}

type StageLogsCardProps = {
  stage: StageSummary | null
  logsQuery: UseQueryResult<TaskLogChunkResponse[]>
  onReload: () => void
}

function StageLogsCard({ stage, logsQuery, onReload }: StageLogsCardProps) {
  const { t } = useTranslation()
  const [levelFilter, setLevelFilter] = useState<"ALL" | "WARN" | "ERROR">("ALL")
  useEffect(() => {
    setLevelFilter("ALL")
  }, [stage?.stageId])
  const logs = logsQuery.data ?? []
  const filteredLogs = useMemo(() => {
    if (levelFilter === "ALL") {
      return logs
    }
    if (levelFilter === "WARN") {
      return logs.filter((chunk) => (chunk.level ?? "INFO").toUpperCase() !== "INFO")
    }
    return logs.filter((chunk) => (chunk.level ?? "INFO").toUpperCase() === "ERROR")
  }, [logs, levelFilter])
  const stageLabel = stage
    ? `${t(`stageTypes.${stage.type}`, { defaultValue: stage.type })} · ${t(`statuses.${stage.status}`, { defaultValue: stage.status })}`
    : t("tasks.logs.selectStage")
  return (
    <Card>
      <CardHeader className="space-y-2 sm:space-y-1">
        <div>
          <CardTitle className="text-base">{t("tasks.logs.stageTitle")}</CardTitle>
          <p className="text-xs text-muted-foreground">{stageLabel}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex gap-1">
            <Button
              type="button"
              variant={levelFilter === "ALL" ? "default" : "outline"}
              size="sm"
              onClick={() => setLevelFilter("ALL")}
              disabled={!stage}
            >
              {t("common.all")}
            </Button>
            <Button
              type="button"
              variant={levelFilter === "WARN" ? "default" : "outline"}
              size="sm"
              onClick={() => setLevelFilter("WARN")}
              disabled={!stage}
            >
              {t("logLevels.WARN")}
            </Button>
            <Button
              type="button"
              variant={levelFilter === "ERROR" ? "default" : "outline"}
              size="sm"
              onClick={() => setLevelFilter("ERROR")}
              disabled={!stage}
            >
              {t("logLevels.ERROR")}
            </Button>
          </div>
          <Button type="button" variant="outline" size="sm" onClick={onReload} disabled={!stage || logsQuery.isFetching}>
            {logsQuery.isFetching ? t("common.loading") : t("common.refresh")}
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        {!stage ? (
          <p className="text-sm text-muted-foreground">{t("tasks.logs.selectStage")}</p>
        ) : logsQuery.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} /> {t("common.loading")}
          </div>
        ) : logsQuery.isError ? (
          <p className="text-sm text-destructive">{t("tasks.logs.error")}</p>
        ) : filteredLogs.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("tasks.logs.empty")}</p>
        ) : (
          filteredLogs.map((chunk) => {
            const level = (chunk.level ?? "INFO").toUpperCase()
            const levelLabel = t(`logLevels.${level}`, { defaultValue: level })
            return (
              <div key={`${chunk.sequence}-${chunk.stream}`} className="rounded-md border border-border p-3 text-sm">
                <div className="mb-1 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
                  <div className="flex items-center gap-2">
                    <span className={cn("rounded-full px-2 py-0.5 font-semibold", LOG_LEVEL_STYLES[level] ?? LOG_LEVEL_STYLES.INFO)}>
                      {levelLabel}
                    </span>
                    <span className="font-mono text-[10px] uppercase tracking-tight text-muted-foreground/80">
                      {chunk.stream}
                    </span>
                  </div>
                  <span>{formatDateTime(chunk.createdAt)}</span>
                </div>
                <pre className="whitespace-pre-wrap break-words text-xs">{chunk.content}</pre>
              </div>
            )
          })
        )}
      </CardContent>
    </Card>
  )
}

const LOG_LEVEL_STYLES: Record<string, string> = {
  INFO: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-300",
  WARN: "bg-amber-500/15 text-amber-600 dark:text-amber-300",
  ERROR: "bg-red-500/15 text-red-600 dark:text-red-300",
}
