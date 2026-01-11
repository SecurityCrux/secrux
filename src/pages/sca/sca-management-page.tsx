import { useEffect, useMemo, useState } from "react"
import { useLocation } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useQuery } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { TaskListPanel } from "@/components/task-list-panel"
import { DependencyGraphTreeView } from "@/components/sca/dependency-graph-tree-view"
import { ScaIssuesTable } from "@/components/sca/sca-issues-table"

import { useTasksQuery } from "@/hooks/queries/use-tasks"
import { downloadScaSbom, getScaDependencyGraph, getScaTaskIssues } from "@/services/sca-service"
import type {
  DependencyGraph,
  DependencyGraphNode,
  FindingListFilters,
  PageResponse,
  ScaIssueSummary,
  TaskListFilters,
} from "@/types/api"

export function ScaManagementPage() {
  const { t } = useTranslation()
  const location = useLocation()
  const urlProjectId = new URLSearchParams(location.search).get("projectId") ?? undefined
  const [filters, setFilters] = useState<TaskListFilters>({ limit: 25, type: "SCA_CHECK", projectId: urlProjectId })
  const tasksQuery = useTasksQuery(filters)
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null)
  const [sbomDownloading, setSbomDownloading] = useState(false)
  const [sbomDownloadError, setSbomDownloadError] = useState<string | null>(null)
  const [issuePage, setIssuePage] = useState<Pick<FindingListFilters, "limit" | "offset">>({
    limit: 10,
    offset: 0,
  })

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
    setIssuePage((prev) => ({ ...prev, offset: 0 }))
    setSbomDownloading(false)
    setSbomDownloadError(null)
  }, [selectedTaskId])

  const selectedTask = useMemo(() => {
    const items = tasksQuery.data?.items ?? []
    return items.find((task) => task.taskId === selectedTaskId) ?? null
  }, [tasksQuery.data, selectedTaskId])

  const shouldPoll = selectedTask?.status === "RUNNING" || selectedTask?.status === "PENDING"

  const issuesQuery = useQuery<PageResponse<ScaIssueSummary>>({
    queryKey: ["sca", selectedTaskId, "issues", issuePage],
    queryFn: () => getScaTaskIssues(selectedTaskId!, issuePage),
    enabled: !!selectedTaskId,
    refetchInterval: shouldPoll ? 7000 : false,
  })

  const graphQuery = useQuery<DependencyGraph>({
    queryKey: ["sca", selectedTaskId, "dependencyGraph"],
    queryFn: () => getScaDependencyGraph(selectedTaskId!),
    enabled: !!selectedTaskId,
    refetchInterval: shouldPoll ? 7000 : false,
  })

  const riskIndexQuery = useQuery<ScaComponentRiskIndex>({
    queryKey: ["sca", selectedTaskId, "componentRisks"],
    queryFn: () => buildScaComponentRiskIndex(selectedTaskId!),
    enabled: !!selectedTaskId && !shouldPoll,
    staleTime: 60_000,
  })

  const riskByNodeId = useMemo(() => {
    if (!graphQuery.data || !riskIndexQuery.data) return null
    return buildGraphRiskIndex(graphQuery.data, riskIndexQuery.data)
  }, [graphQuery.data, riskIndexQuery.data])

  const handleDownloadSbom = async () => {
    if (!selectedTask) return
    setSbomDownloadError(null)
    setSbomDownloading(true)
    try {
      const { blob, fileName } = await downloadScaSbom(selectedTask.taskId)
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement("a")
      link.href = url
      link.download = fileName
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.URL.revokeObjectURL(url)
    } catch {
      setSbomDownloadError(t("sca.sbom.downloadError"))
    } finally {
      setSbomDownloading(false)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("sca.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("sca.subtitle")}</p>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[360px,1fr]">
        <TaskListPanel
          tasksQuery={tasksQuery}
          filters={filters}
          onFiltersChange={setFilters}
          selectedTaskId={selectedTaskId}
          onSelectTask={setSelectedTaskId}
          titleKey="sca.taskListTitle"
        />
        {selectedTask ? (
          <div className="space-y-4">
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center justify-between gap-2 text-base">
                  <span>{t("sca.dependencyGraph.title")}</span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={sbomDownloading || shouldPoll}
                    onClick={handleDownloadSbom}
                  >
                    {sbomDownloading ? t("sca.sbom.downloading") : t("sca.sbom.download")}
                  </Button>
                </CardTitle>
              </CardHeader>
              <CardContent>
                {sbomDownloadError ? <p className="mb-2 text-sm text-destructive">{sbomDownloadError}</p> : null}
                {graphQuery.isLoading ? (
                  <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Spinner size={16} />
                    {t("common.loading")}
                  </div>
                ) : graphQuery.data ? (
                  <DependencyGraphTreeView key={selectedTask.taskId} graph={graphQuery.data} riskByNodeId={riskByNodeId} />
                ) : (
                  <p className="text-sm text-muted-foreground">{t("sca.dependencyGraph.empty")}</p>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-base">{t("sca.issues.title")}</CardTitle>
              </CardHeader>
              <CardContent className="space-y-4">
                <ScaIssuesTable
                  key={selectedTask.taskId}
                  taskId={selectedTask.taskId}
                  query={issuesQuery}
                  page={issuePage}
                  onPageChange={setIssuePage}
                />
              </CardContent>
            </Card>
          </div>
        ) : (
          <Card className="flex items-center justify-center text-sm text-muted-foreground">
            <CardContent>{t("sca.emptySelection")}</CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}

/*
type ScaTaskCreateDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (task: TaskSummary) => void
}

function ScaTaskCreateDialog({ open, onOpenChange, onCreated }: ScaTaskCreateDialogProps) {
  const { t } = useTranslation()
  const projectsQuery = useProjectsQuery()
  const executorsQuery = useExecutorsQuery({ status: "READY" })
  const [repositories, setRepositories] = useState<RepositoryResponse[]>([])
  const [repoLoading, setRepoLoading] = useState(false)
  const [gitMeta, setGitMeta] = useState<RepositoryGitMetadata | null>(null)
  const [gitLoading, setGitLoading] = useState(false)
  const [gitError, setGitError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [archiveFile, setArchiveFile] = useState<File | null>(null)
  const [sbomFile, setSbomFile] = useState<File | null>(null)
  const [archiveUpload, setArchiveUpload] = useState<{ uploadId: string; fileName: string } | null>(null)
  const [sbomUpload, setSbomUpload] = useState<{ uploadId: string; fileName: string } | null>(null)

  const [form, setForm] = useState({
    projectId: "",
    targetType: "git" as ScaTargetType,
    repoId: "",
    executorId: "",
    correlationId: "",
    refMode: "latest" as RefMode,
    branch: "",
    tag: "",
    commit: "",
    filesystemPath: "",
    imageRef: "",
  })

  const resetForm = () => {
    setForm({
      projectId: "",
      targetType: "git",
      repoId: "",
      executorId: "",
      correlationId: `sca-${Date.now()}`,
      refMode: "latest",
      branch: "",
      tag: "",
      commit: "",
      filesystemPath: "",
      imageRef: "",
    })
    setRepositories([])
    setGitMeta(null)
    setGitError(null)
    setError(null)
    setArchiveFile(null)
    setSbomFile(null)
    setArchiveUpload(null)
    setSbomUpload(null)
  }

  useEffect(() => {
    if (!open) {
      resetForm()
      return
    }
    if (!form.projectId && projectsQuery.data && projectsQuery.data.length > 0) {
      const nextProject = projectsQuery.data[0]
      setForm((prev) => ({ ...prev, projectId: nextProject.projectId }))
      void loadRepositories(nextProject.projectId)
    }
    if (!form.correlationId) {
      setForm((prev) => ({ ...prev, correlationId: `sca-${Date.now()}` }))
    }
  }, [open, projectsQuery.data])

  useEffect(() => {
    if (!form.projectId || form.targetType !== "git") {
      setRepositories([])
      setForm((prev) => ({ ...prev, repoId: "" }))
      return
    }
    void loadRepositories(form.projectId)
  }, [form.projectId, form.targetType])

  useEffect(() => {
    if (!form.projectId || !form.repoId || form.targetType !== "git") {
      setGitMeta(null)
      return
    }
    void fetchGitMeta(form.projectId, form.repoId, false)
  }, [form.projectId, form.repoId, form.targetType])

  const buildRefSelection = (): { ok: true; data: RefSelection } | { ok: false; error: string } => {
    if (form.refMode === "latest") {
      if (!gitMeta?.defaultBranch) {
        return { ok: false, error: t("sca.createDialog.errors.noDefaultBranch") }
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
      if (!form.branch) return { ok: false, error: t("sca.createDialog.errors.branch") }
      return { ok: true, data: { sourceRefType: "BRANCH", sourceRef: form.branch, gitRef: form.branch } }
    }
    if (form.refMode === "tag") {
      if (!form.tag) return { ok: false, error: t("sca.createDialog.errors.tag") }
      return { ok: true, data: { sourceRefType: "TAG", sourceRef: form.tag, gitRef: form.tag } }
    }
    if (form.refMode === "commit") {
      if (!form.commit) return { ok: false, error: t("sca.createDialog.errors.commit") }
      return { ok: true, data: { sourceRefType: "COMMIT", sourceRef: form.commit, commitSha: form.commit, gitRef: form.commit } }
    }
    return { ok: false, error: t("sca.createDialog.errors.gitUnknown") }
  }

  const buildSpecPayload = (selection?: RefSelection): { spec: TaskSpecPayload; repoId?: string; sourceRefType: SourceRefType; sourceRef?: string; commitSha?: string } => {
    if (form.targetType === "git") {
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
        },
      }
    }

    if (form.targetType === "archive") {
      return {
        sourceRefType: "UPLOAD",
        sourceRef: archiveUpload?.uploadId,
        spec: {
          source: { archive: { uploadId: archiveUpload?.uploadId } },
          ruleSelector: { mode: "AUTO" },
        },
      }
    }

    if (form.targetType === "filesystem") {
      const path = form.filesystemPath.trim()
      return {
        sourceRefType: "UPLOAD",
        sourceRef: path,
        spec: {
          source: { filesystem: { path } },
          ruleSelector: { mode: "AUTO" },
        },
      }
    }

    if (form.targetType === "image") {
      const ref = form.imageRef.trim()
      return {
        sourceRefType: "UPLOAD",
        sourceRef: ref,
        spec: {
          source: { image: { ref } },
          ruleSelector: { mode: "AUTO" },
        },
      }
    }

    return {
      sourceRefType: "UPLOAD",
      sourceRef: sbomUpload?.uploadId,
      spec: {
        source: { sbom: { uploadId: sbomUpload?.uploadId } },
        ruleSelector: { mode: "AUTO" },
      },
    }
  }

  const createMutation = useMutation<TaskSummary, unknown, CreateTaskPayload>({
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
      setArchiveUpload({ uploadId: data.uploadId, fileName: data.fileName })
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "upload failed")
    },
  })

  const sbomUploadMutation = useMutation({
    mutationFn: (file: File) => uploadScaSbom(form.projectId, file),
    onSuccess: (data) => {
      setSbomUpload({ uploadId: data.uploadId, fileName: data.fileName })
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
        setForm((prev) => ({ ...prev, branch: data.defaultBranch ?? "" }))
      }
      if (data?.headCommit?.commitId) {
        setForm((prev) => ({ ...prev, commit: data.headCommit?.commitId ?? "" }))
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
      setError(t("sca.createDialog.errors.project"))
      return
    }
    if (!form.correlationId.trim()) {
      setError(t("sca.createDialog.errors.correlation"))
      return
    }

    if (form.targetType === "git") {
      if (!form.repoId) {
        setError(t("sca.createDialog.errors.repository"))
        return
      }
      const refResult = buildRefSelection()
      if (!refResult.ok) {
        setError(refResult.error)
        return
      }
      const built = buildSpecPayload(refResult.data)
      createMutation.mutate({
        projectId: form.projectId,
        repoId: built.repoId,
        executorId: form.executorId || undefined,
        type: "SCA_CHECK",
        engine: "trivy",
        correlationId: form.correlationId.trim(),
        sourceRefType: built.sourceRefType,
        sourceRef: built.sourceRef,
        commitSha: built.commitSha,
        spec: built.spec,
      })
      return
    }

    if (form.targetType === "archive") {
      if (!archiveUpload?.uploadId) {
        setError(t("sca.createDialog.errors.archiveUpload"))
        return
      }
    }
    if (form.targetType === "sbom") {
      if (!sbomUpload?.uploadId) {
        setError(t("sca.createDialog.errors.sbomUpload"))
        return
      }
    }
    if (form.targetType === "filesystem" && !form.filesystemPath.trim()) {
      setError(t("sca.createDialog.errors.filesystemPath"))
      return
    }
    if (form.targetType === "image" && !form.imageRef.trim()) {
      setError(t("sca.createDialog.errors.imageRef"))
      return
    }

    const built = buildSpecPayload()
    createMutation.mutate({
      projectId: form.projectId,
      executorId: form.executorId || undefined,
      type: "SCA_CHECK",
      engine: "trivy",
      correlationId: form.correlationId.trim(),
      sourceRefType: built.sourceRefType,
      sourceRef: built.sourceRef,
      spec: built.spec,
    })
  }

  const canUploadArchive = Boolean(form.projectId && archiveFile && !archiveUploadMutation.isPending)
  const canUploadSbom = Boolean(form.projectId && sbomFile && !sbomUploadMutation.isPending)

  const renderTargetFields = () => {
    if (form.targetType === "git") {
      return (
        <div className="space-y-3">
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.repository")}</label>
            <select
              className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              value={form.repoId}
              disabled={repoLoading}
              onChange={(event) => setForm((prev) => ({ ...prev, repoId: event.target.value }))}
            >
              <option value="">{t("common.selectPlaceholder")}</option>
              {repositories.map((repo) => (
                <option key={repo.repoId} value={repo.repoId}>
                  {repo.remoteUrl ?? repo.repoId}
                </option>
              ))}
            </select>
            {gitError ? <p className="mt-1 text-xs text-destructive">{gitError}</p> : null}
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.refMode")}</label>
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={form.refMode}
                onChange={(event) => setForm((prev) => ({ ...prev, refMode: event.target.value as RefMode }))}
              >
                {REF_MODES.map((mode) => (
                  <option key={mode} value={mode}>
                    {t(`sca.createDialog.refModes.${mode}`)}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.refValue")}</label>
              <Input
                value={
                  form.refMode === "branch"
                    ? form.branch
                    : form.refMode === "tag"
                      ? form.tag
                      : form.refMode === "commit"
                        ? form.commit
                        : gitMeta?.defaultBranch ?? ""
                }
                disabled={form.refMode === "latest"}
                onChange={(event) => {
                  const value = event.target.value
                  setForm((prev) => {
                    if (prev.refMode === "branch") return { ...prev, branch: value }
                    if (prev.refMode === "tag") return { ...prev, tag: value }
                    if (prev.refMode === "commit") return { ...prev, commit: value }
                    return prev
                  })
                }}
              />
              {gitLoading ? (
                <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
                  <Spinner size={14} />
                  {t("common.loading")}
                </div>
              ) : null}
            </div>
          </div>
        </div>
      )
    }

    if (form.targetType === "archive") {
      return (
        <div className="space-y-3">
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.archive")}</label>
            <input
              type="file"
              accept=".zip"
              className="mt-2 block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border file:border-input file:bg-background file:px-3 file:py-2 file:text-sm file:font-medium"
              onChange={(event) => setArchiveFile(event.target.files?.[0] ?? null)}
            />
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              disabled={!canUploadArchive}
              onClick={() => archiveFile && archiveUploadMutation.mutate(archiveFile)}
            >
              {archiveUploadMutation.isPending ? t("common.loading") : t("sca.createDialog.upload")}
            </Button>
            {archiveUpload ? (
              <Badge variant="secondary" className="break-all">
                {archiveUpload.fileName} ({archiveUpload.uploadId})
              </Badge>
            ) : null}
          </div>
        </div>
      )
    }

    if (form.targetType === "filesystem") {
      return (
        <div>
          <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.filesystemPath")}</label>
          <Input
            className="mt-1"
            value={form.filesystemPath}
            onChange={(event) => setForm((prev) => ({ ...prev, filesystemPath: event.target.value }))}
          />
          <p className="mt-1 text-xs text-muted-foreground">{t("sca.createDialog.filesystemHint")}</p>
        </div>
      )
    }

    if (form.targetType === "image") {
      return (
        <div>
          <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.imageRef")}</label>
          <Input className="mt-1" value={form.imageRef} onChange={(event) => setForm((prev) => ({ ...prev, imageRef: event.target.value }))} />
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
            onChange={(event) => setSbomFile(event.target.files?.[0] ?? null)}
          />
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" disabled={!canUploadSbom} onClick={() => sbomFile && sbomUploadMutation.mutate(sbomFile)}>
            {sbomUploadMutation.isPending ? t("common.loading") : t("sca.createDialog.upload")}
          </Button>
          {sbomUpload ? (
            <Badge variant="secondary" className="break-all">
              {sbomUpload.fileName} ({sbomUpload.uploadId})
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
          <DialogTitle>{t("sca.createDialog.title")}</DialogTitle>
          <DialogDescription>{t("sca.createDialog.subtitle")}</DialogDescription>
        </DialogHeader>

        <form className="space-y-4" onSubmit={submit}>
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.project")}</label>
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
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.targetType")}</label>
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={form.targetType}
                onChange={(event) => setForm((prev) => ({ ...prev, targetType: event.target.value as ScaTargetType }))}
              >
                {SCA_TARGET_TYPES.map((tt) => (
                  <option key={tt} value={tt}>
                    {t(`sca.createDialog.targetTypes.${tt}`)}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {renderTargetFields()}

          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.correlationId")}</label>
              <Input value={form.correlationId} onChange={(event) => setForm((prev) => ({ ...prev, correlationId: event.target.value }))} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("sca.createDialog.executor")}</label>
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={form.executorId}
                onChange={(event) => setForm((prev) => ({ ...prev, executorId: event.target.value }))}
              >
                <option value="">{t("common.selectPlaceholder")}</option>
                {executorsQuery.data?.map((executor) => (
                  <option key={executor.executorId} value={executor.executorId}>
                    {executor.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {error ? <p className="text-sm text-destructive">{error}</p> : null}

          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={createMutation.isPending}>
              {createMutation.isPending ? t("common.loading") : t("sca.createDialog.submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
*/

type ScaComponentRiskIndex = {
  byName: Record<string, string>
  byNameVersion: Record<string, string>
}

const SCA_SEVERITY_RANK: Record<string, number> = {
  CRITICAL: 5,
  HIGH: 4,
  MEDIUM: 3,
  LOW: 2,
  INFO: 1,
}

function normalizeScaSeverity(raw: unknown): string | null {
  if (typeof raw !== "string") return null
  const value = raw.trim().toUpperCase()
  if (value === "CRITICAL" || value === "HIGH" || value === "MEDIUM" || value === "LOW" || value === "INFO") return value
  return null
}

function maxScaSeverity(a: string | undefined, b: string): string {
  if (!a) return b
  const ar = SCA_SEVERITY_RANK[a] ?? 0
  const br = SCA_SEVERITY_RANK[b] ?? 0
  return ar >= br ? a : b
}

function derivePackageKeys(raw: string): string[] {
  const base = raw.trim().toLowerCase()
  if (!base) return []
  const keys = new Set<string>()
  keys.add(base)
  if (base.includes(":")) {
    const parts = base.split(":").filter(Boolean)
    const tail = parts[parts.length - 1]
    if (tail) keys.add(tail)
    if (parts.length >= 2) keys.add(parts.join("/"))
  }
  if (base.includes("/")) {
    const parts = base.split("/").filter(Boolean)
    const tail = parts[parts.length - 1]
    if (tail) keys.add(tail)
    if (parts.length >= 2) keys.add(parts.join(":"))
  }
  return Array.from(keys)
}

type ParsedPurl = {
  type: string
  namespace?: string
  name: string
  version?: string
}

function parsePurl(raw: string): ParsedPurl | null {
  const trimmed = raw.trim()
  if (!trimmed.startsWith("pkg:")) return null
  const withoutPrefix = trimmed.slice("pkg:".length)
  const base = withoutPrefix.split("?")[0].split("#")[0]
  const firstSlash = base.indexOf("/")
  if (firstSlash <= 0) return null
  const type = base.slice(0, firstSlash)
  const rest = base.slice(firstSlash + 1)
  if (!rest) return null

  const at = rest.lastIndexOf("@")
  const namePart = at >= 0 ? rest.slice(0, at) : rest
  const version = at >= 0 ? rest.slice(at + 1) : undefined

  const segments = namePart.split("/").filter(Boolean)
  const name = segments.pop() ?? ""
  if (!name) return null
  const namespace = segments.length > 0 ? segments.join("/") : undefined
  return {
    type,
    namespace,
    name,
    version: version?.trim() || undefined,
  }
}

function deriveNodeKeys(node: DependencyGraphNode): string[] {
  const keys = new Set<string>()
  if (node.name) {
    derivePackageKeys(node.name).forEach((key) => keys.add(key))
  }
  if (node.purl) {
    const parsed = parsePurl(node.purl)
    if (parsed) {
      const name = parsed.name.toLowerCase()
      keys.add(name)
      if (parsed.namespace) {
        const ns = parsed.namespace.toLowerCase()
        keys.add(`${ns}:${name}`)
        keys.add(`${ns}/${name}`)
      }
    } else {
      derivePackageKeys(node.purl).forEach((key) => keys.add(key))
    }
  }
  return Array.from(keys)
}

async function buildScaComponentRiskIndex(taskId: string): Promise<ScaComponentRiskIndex> {
  const byName: Record<string, string> = {}
  const byNameVersion: Record<string, string> = {}
  const limit = 200
  let offset = 0
  let total = Number.POSITIVE_INFINITY

  while (offset < total) {
    const page = await getScaTaskIssues(taskId, { limit, offset })
    total = page.total
    if (page.items.length === 0) break

    for (const issue of page.items) {
      if (issue.status === "RESOLVED" || issue.status === "FALSE_POSITIVE") continue
      const severity = normalizeScaSeverity(issue.severity)
      if (!severity) continue
      const pkgName = (issue.packageName ?? issue.componentName ?? "").trim()
      if (!pkgName) continue
      const installedVersion = (issue.installedVersion ?? issue.componentVersion ?? "").trim().toLowerCase()
      const pkgKeys = derivePackageKeys(pkgName)
      if (pkgKeys.length === 0) continue

      for (const pkgKey of pkgKeys) {
        byName[pkgKey] = maxScaSeverity(byName[pkgKey], severity)
        if (installedVersion) {
          const key = `${pkgKey}@@${installedVersion}`
          byNameVersion[key] = maxScaSeverity(byNameVersion[key], severity)
        }
      }
    }

    offset += page.items.length
  }

  return { byName, byNameVersion }
}

function buildGraphRiskIndex(graph: DependencyGraph, risk: ScaComponentRiskIndex): Record<string, string> {
  const byNodeId: Record<string, string> = {}
  for (const node of graph.nodes) {
    const keys = deriveNodeKeys(node)
    if (keys.length === 0) continue
    const version = (node.version ?? "").trim().toLowerCase()
    let severity: string | undefined

    if (version) {
      for (const key of keys) {
        const found = risk.byNameVersion[`${key}@@${version}`]
        if (found) {
          severity = found
          break
        }
      }
    }

    if (!severity) {
      for (const key of keys) {
        const found = risk.byName[key]
        if (found) {
          severity = found
          break
        }
      }
    }

    if (severity) {
      byNodeId[node.id] = severity
    }
  }
  return byNodeId
}
