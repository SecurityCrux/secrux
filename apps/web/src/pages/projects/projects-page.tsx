import { useTranslation } from "react-i18next"
import { useState, useEffect, useMemo, useRef } from "react"
import { useMutation } from "@tanstack/react-query"
import { Link } from "react-router-dom"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { useProjectsQuery } from "@/hooks/queries/use-projects"
import { Spinner } from "@/components/ui/spinner"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  createProject,
  updateProject,
  listRepositories,
  createRepository,
  updateRepository as updateRepositoryRequest,
  deleteRepository as deleteRepositoryRequest,
  deleteProject,
} from "@/services/project-service"
import type {
  Project,
  ProjectUpsertPayload,
  RepositoryResponse,
  RepositoryUpdatePayload,
  RepositoryUpsertPayload,
  RepositorySourceMode,
  RepositoryScmType,
  RepositoryGitAuthMode,
  RepositoryGitAuthPayload,
} from "@/types/api"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog"
import { cn } from "@/lib/utils"

export function ProjectsPage() {
  const { t } = useTranslation()
  const projectsQuery = useProjectsQuery()
  const [editingProject, setEditingProject] = useState<Project | null>(null)

  const [dialogOpen, setDialogOpen] = useState(false)

  const openCreateDialog = () => {
    setEditingProject(null)
    setDialogOpen(true)
  }

  const openEditDialog = (project: Project) => {
    setEditingProject(project)
    setDialogOpen(true)
  }

  const handleSuccess = () => {
    setDialogOpen(false)
    setEditingProject(null)
    void projectsQuery.refetch()
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("projects.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("projects.subtitle")}</p>
        </div>
        <Button onClick={openCreateDialog}>{t("projects.form.createTitle")}</Button>
      </div>
      <ProjectDialog
        project={editingProject}
        open={dialogOpen}
        onOpenChange={(open) => {
          setDialogOpen(open)
          if (!open) {
            setEditingProject(null)
          }
        }}
        onSuccess={handleSuccess}
      />
      <Card>
        <CardHeader>
          <CardTitle className="text-base font-semibold">{t("projects.tableTitle")}</CardTitle>
        </CardHeader>
        <CardContent>
          {projectsQuery.isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Spinner size={18} /> {t("common.loading")}
            </div>
          ) : projectsQuery.isError ? (
            <p className="text-sm text-destructive">{t("projects.error")}</p>
          ) : projectsQuery.data && projectsQuery.data.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="py-2 text-left">{t("projects.columns.name")}</th>
                    <th className="py-2 text-left">{t("projects.columns.codeOwners")}</th>
                    <th className="py-2 text-left">{t("projects.columns.createdAt")}</th>
                    <th className="py-2 text-left" />
                  </tr>
                </thead>
                <tbody>
                  {projectsQuery.data.map((project) => (
                    <ProjectRow
                      key={project.projectId}
                      project={project}
                      onEdit={() => openEditDialog(project)}
                      onDeleted={() => void projectsQuery.refetch()}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("projects.empty")}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function ProjectRow({
  project,
  onEdit,
  onDeleted,
}: {
  project: Project
  onEdit: () => void
  onDeleted: () => void
}) {
  const { t } = useTranslation()
  const [repoDialogOpen, setRepoDialogOpen] = useState(false)
  const [repositories, setRepositories] = useState<RepositoryResponse[] | null>(null)
  const [repoError, setRepoError] = useState<string | null>(null)
  const [repoLoading, setRepoLoading] = useState(false)
  const deleteMutation = useMutation({
    mutationFn: () => deleteProject(project.projectId),
    onSuccess: () => {
      onDeleted()
    },
  })

  const loadRepos = async () => {
    setRepoLoading(true)
    setRepoError(null)
    try {
      const data = await listRepositories(project.projectId)
      setRepositories(data)
    } catch (error) {
      setRepoError(error instanceof Error ? error.message : "unknown")
    } finally {
      setRepoLoading(false)
    }
  }

  const openRepos = async () => {
    setRepoDialogOpen(true)
    if (!repositories) {
      await loadRepos()
    }
  }

  return (
    <>
      <tr className="border-b last:border-b-0">
        <td className="py-3 font-medium">{project.name}</td>
        <td className="py-3 text-muted-foreground">
          {project.codeOwners.length > 0 ? project.codeOwners.join(", ") : "—"}
        </td>
        <td className="py-3 text-muted-foreground">{new Date(project.createdAt).toLocaleString()}</td>
        <td className="py-3 text-right space-x-2">
          <Button variant="ghost" size="sm" onClick={onEdit}>
            {t("projects.actions.edit")}
          </Button>
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/projects/${project.projectId}`}>{t("projects.actions.details")}</Link>
          </Button>
          <Button variant="ghost" size="sm" onClick={openRepos}>
            {t("repositories.title")}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className="text-destructive hover:text-destructive"
            disabled={deleteMutation.isPending}
            onClick={() => {
              if (!window.confirm(t("projects.confirmDelete", { name: project.name }))) {
                return
              }
              deleteMutation.mutate()
            }}
          >
            {deleteMutation.isPending ? t("common.loading") : t("projects.actions.delete")}
          </Button>
        </td>
      </tr>
      <RepositoryDialog
        projectId={project.projectId}
        open={repoDialogOpen}
        onOpenChange={(open) => setRepoDialogOpen(open)}
        repositories={repositories}
        loading={repoLoading}
        error={repoError}
        onRefresh={loadRepos}
      />
    </>
  )
}

type ProjectDialogProps = {
  project: Project | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onSuccess: () => void
}

function ProjectDialog({ project, open, onOpenChange, onSuccess }: ProjectDialogProps) {
  const { t } = useTranslation()
  const [name, setName] = useState("")
  const [codeOwners, setCodeOwners] = useState("")
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setName(project?.name ?? "")
      setCodeOwners(project?.codeOwners?.join(", ") ?? "")
      setError(null)
    }
  }, [open, project])

  const mutation = useMutation({
    mutationFn: async (payload: ProjectUpsertPayload) => {
      if (project) {
        return updateProject(project.projectId, payload)
      }
      return createProject(payload)
    },
    onSuccess: () => {
      onSuccess()
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const submitDisabled = mutation.isPending || name.trim().length === 0

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{project ? t("projects.form.editTitle") : t("projects.form.createTitle")}</DialogTitle>
          <DialogDescription>{t("projects.subtitle")}</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            mutation.mutate({
              name: name.trim(),
              codeOwners:
                codeOwners
                  .split(",")
                  .map((owner) => owner.trim())
                  .filter(Boolean) ?? [],
            })
          }}
        >
          <div>
            <label className="block text-sm font-medium">{t("projects.form.name")}</label>
            <Input className="mt-1" value={name} onChange={(e) => setName(e.target.value)} required />
          </div>
          <div>
            <label className="block text-sm font-medium">{t("projects.form.codeOwners")}</label>
            <Input
              className="mt-1"
              value={codeOwners}
              placeholder="dev1@example.com, dev2@example.com"
              onChange={(e) => setCodeOwners(e.target.value)}
            />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("projects.actions.cancel")}
            </Button>
            <Button type="submit" disabled={submitDisabled}>
              {project ? t("projects.form.save") : t("projects.form.create")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type RepositoryDialogProps = {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  repositories: RepositoryResponse[] | null
  loading: boolean
  error: string | null
  onRefresh: () => Promise<void>
}

function RepositoryDialog({
  projectId,
  open,
  onOpenChange,
  repositories,
  loading,
  error,
  onRefresh,
}: RepositoryDialogProps) {
  const { t } = useTranslation()
  const [sourceMode, setSourceMode] = useState<RepositorySourceMode>("REMOTE")
  const [remoteUrl, setRemoteUrl] = useState("")
  const [scmType, setScmType] = useState<RepositoryScmType>("git")
  const [defaultBranch, setDefaultBranch] = useState("")
  const [secretRef, setSecretRef] = useState("")
  const [authMode, setAuthMode] = useState<RepositoryGitAuthMode>("NONE")
  const [authUsername, setAuthUsername] = useState("")
  const [authPassword, setAuthPassword] = useState("")
  const [authToken, setAuthToken] = useState("")
  const [authConfigured, setAuthConfigured] = useState(false)
  const [authReplace, setAuthReplace] = useState(false)
  const [initialAuthMode, setInitialAuthMode] = useState<RepositoryGitAuthMode>("NONE")
  const [uploadKey, setUploadKey] = useState("")
  const [uploadChecksum, setUploadChecksum] = useState("")
  const [uploadSize, setUploadSize] = useState("")
  const [uploadFileLabel, setUploadFileLabel] = useState("")
  const [digestingFile, setDigestingFile] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)
  const [view, setView] = useState<"list" | "form">("list")
  const [editingRepo, setEditingRepo] = useState<RepositoryResponse | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const resetForm = (nextView: "list" | "form" = "list") => {
    setSourceMode("REMOTE")
    setRemoteUrl("")
    setScmType("git")
    setDefaultBranch("")
    setSecretRef("")
    setAuthMode("NONE")
    setAuthUsername("")
    setAuthPassword("")
    setAuthToken("")
    setAuthConfigured(false)
    setAuthReplace(false)
    setInitialAuthMode("NONE")
    setUploadKey("")
    setUploadChecksum("")
    setUploadSize("")
    setUploadFileLabel("")
    setServerError(null)
    setView(nextView)
    setEditingRepo(null)
    setDigestingFile(false)
    if (fileInputRef.current) {
      fileInputRef.current.value = ""
    }
  }

  const beginCreate = () => {
    resetForm("form")
  }

  const beginEdit = (repo: RepositoryResponse) => {
    setEditingRepo(repo)
    setSourceMode(repo.sourceMode)
    setRemoteUrl(repo.remoteUrl ?? "")
    setScmType((repo.scmType as RepositoryScmType) ?? "git")
    setDefaultBranch(repo.defaultBranch ?? "")
    setSecretRef(repo.secretRef ?? "")
    setAuthMode((repo.gitAuthMode as RepositoryGitAuthMode) ?? "NONE")
    setInitialAuthMode((repo.gitAuthMode as RepositoryGitAuthMode) ?? "NONE")
    setAuthConfigured(repo.gitAuthConfigured ?? false)
    setAuthReplace(false)
    setAuthUsername("")
    setAuthPassword("")
    setAuthToken("")
    setUploadKey(repo.uploadKey ?? "")
    setUploadChecksum(repo.uploadChecksum ?? "")
    setUploadSize(repo.uploadSize ? String(repo.uploadSize) : "")
    setUploadFileLabel("")
    setServerError(null)
    setView("form")
  }

  useEffect(() => {
    if (!open) {
      resetForm()
    }
  }, [open])

  useEffect(() => {
    setAuthUsername("")
    setAuthPassword("")
    setAuthToken("")
    if (editingRepo) {
      setAuthReplace(authMode !== initialAuthMode && authMode !== "NONE")
    }
  }, [authMode, editingRepo, initialAuthMode])

  const scmOptions = useMemo(
    () => [
      { value: "git", label: t("repositories.scmLabels.git") },
      { value: "github", label: t("repositories.scmLabels.github") },
      { value: "gitlab", label: t("repositories.scmLabels.gitlab") },
      { value: "gerrit", label: t("repositories.scmLabels.gerrit") },
      { value: "bitbucket", label: t("repositories.scmLabels.bitbucket") },
    ],
    [t]
  )

  const createMutation = useMutation({
    mutationFn: (payload: RepositoryUpsertPayload) => createRepository(projectId, payload),
    onSuccess: () => {
      void onRefresh()
      resetForm()
    },
    onError: (err: unknown) => {
      setServerError(err instanceof Error ? err.message : "unknown")
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({ repoId, payload }: { repoId: string; payload: RepositoryUpdatePayload }) =>
      updateRepositoryRequest(projectId, repoId, payload),
    onSuccess: () => {
      void onRefresh()
      resetForm()
    },
    onError: (err: unknown) => {
      setServerError(err instanceof Error ? err.message : "unknown")
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (repoId: string) => deleteRepositoryRequest(projectId, repoId),
    onSuccess: () => {
      void onRefresh()
    },
    onError: (err: unknown) => {
      setServerError(err instanceof Error ? err.message : "unknown")
    },
  })

  const needsRemoteFields = sourceMode === "REMOTE" || sourceMode === "MIXED"
  const needsUploadFields = sourceMode === "UPLOAD" || sourceMode === "MIXED"
  const isSaving = createMutation.isPending || updateMutation.isPending
  const uploadSizeNumber = uploadSize ? Number(uploadSize) : undefined
  const uploadSizeReadable =
    uploadSizeNumber !== undefined && Number.isFinite(uploadSizeNumber) && uploadSizeNumber > 0
      ? formatBytes(uploadSizeNumber)
      : undefined
  const remoteValid = !needsRemoteFields || (remoteUrl.trim().length > 0 && Boolean(scmType))
  const uploadValid =
    !needsUploadFields ||
    (uploadKey.trim().length > 0 &&
      uploadChecksum.trim().length > 0 &&
      uploadSizeNumber !== undefined &&
      Number.isFinite(uploadSizeNumber) &&
      uploadSizeNumber > 0)
  const authInputsRequired =
    authMode !== "NONE" &&
    (!editingRepo || authMode !== initialAuthMode || authReplace)
  const authInputsEnabled =
    authMode !== "NONE" &&
    (!editingRepo || authMode !== initialAuthMode || authReplace)
  const authValid =
    !authInputsRequired ||
    (authMode === "BASIC"
      ? authUsername.trim().length > 0 && authPassword.trim().length > 0
      : authMode === "TOKEN"
        ? authToken.trim().length > 0
        : true)
  const submitDisabled = isSaving || digestingFile || !remoteValid || !uploadValid || !authValid

  const handleArchiveSelect = async (file: File | null) => {
    if (!file) {
      setUploadFileLabel("")
      return
    }
    if (typeof window === "undefined" || !window.crypto?.subtle) {
      setServerError(t("repositories.errors.cryptoUnavailable"))
      return
    }
    try {
      setDigestingFile(true)
      setServerError(null)
      const buffer = await file.arrayBuffer()
      const digest = await window.crypto.subtle.digest("SHA-256", buffer)
      const hashArray = Array.from(new Uint8Array(digest))
      const hashHex = hashArray.map((b) => b.toString(16).padStart(2, "0")).join("")
      setUploadChecksum(hashHex)
      setUploadSize(String(file.size))
      setUploadFileLabel(file.name)
      if (!uploadKey) {
        setUploadKey(file.name)
      }
    } catch (err) {
      setServerError(err instanceof Error ? err.message : "Failed to process archive")
    } finally {
      setDigestingFile(false)
    }
  }

  const computeGitAuthPayload = (): RepositoryGitAuthPayload | null | "invalid" => {
    const needsUpdate =
      (!editingRepo && authMode !== "NONE") ||
      (editingRepo && (authMode !== initialAuthMode || authReplace))
    if (!needsUpdate) {
      return null
    }
    if (authMode === "NONE") {
      return editingRepo && authConfigured ? { mode: "NONE" } : null
    }
    if (!authInputsEnabled) {
      return "invalid"
    }
    if (authMode === "BASIC") {
      if (!authUsername.trim() || !authPassword.trim()) {
        setServerError(t("repositories.form.authErrors.basic"))
        return "invalid"
      }
      return {
        mode: "BASIC",
        username: authUsername.trim(),
        password: authPassword,
      }
    }
    if (authMode === "TOKEN") {
      if (!authToken.trim()) {
        setServerError(t("repositories.form.authErrors.token"))
        return "invalid"
      }
      return {
        mode: "TOKEN",
        token: authToken.trim(),
        username: authUsername.trim() || undefined,
      }
    }
    return null
  }

  const submitForm = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setServerError(null)
    const gitAuthPayload = computeGitAuthPayload()
    if (gitAuthPayload === "invalid") {
      return
    }
    const basePayload: RepositoryUpdatePayload = {
      sourceMode,
      remoteUrl: needsRemoteFields ? remoteUrl.trim() || undefined : undefined,
      scmType: needsRemoteFields ? scmType : undefined,
      defaultBranch: needsRemoteFields ? defaultBranch.trim() || undefined : undefined,
      secretRef: secretRef.trim() || undefined,
      uploadKey: needsUploadFields ? uploadKey.trim() || undefined : undefined,
      uploadChecksum: needsUploadFields ? uploadChecksum.trim() || undefined : undefined,
      uploadSize:
        needsUploadFields &&
        uploadSizeNumber !== undefined &&
        Number.isFinite(uploadSizeNumber) &&
        uploadSizeNumber > 0
          ? uploadSizeNumber
          : undefined,
    }
    const payloadWithAuth: RepositoryUpdatePayload =
      gitAuthPayload ? { ...basePayload, gitAuth: gitAuthPayload } : basePayload
    if (editingRepo) {
      updateMutation.mutate({ repoId: editingRepo.repoId, payload: payloadWithAuth })
      return
    }
    const payload: RepositoryUpsertPayload = {
      projectId,
      ...payloadWithAuth,
    }
    createMutation.mutate(payload)
  }

  const renderScmLabel = (value: RepositoryScmType | null | undefined) =>
    value ? t(`repositories.scmLabels.${value}`) : "—"

  const renderAuthMode = (mode: RepositoryGitAuthMode, configured: boolean) => {
    if (!configured || mode === "NONE") {
      return t("repositories.form.authModes.none")
    }
    if (mode === "BASIC") {
      return t("repositories.form.authModes.basic")
    }
    if (mode === "TOKEN") {
      return t("repositories.form.authModes.token")
    }
    return t("repositories.form.authModes.none")
  }

  const handleDeleteRepo = (repo: RepositoryResponse) => {
    if (
      !window.confirm(
        t("repositories.confirmDelete", {
          name: repo.remoteUrl ?? repo.repoId,
        })
      )
    ) {
      return
    }
    deleteMutation.mutate(repo.repoId)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>{t("repositories.title")}</DialogTitle>
          <DialogDescription>{t("repositories.subtitle")}</DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => resetForm("list")} disabled={view === "list"}>
              {t("repositories.actions.viewList")}
            </Button>
            <Button variant="ghost" size="sm" onClick={beginCreate} disabled={view === "form" && !editingRepo}>
              {t("repositories.actions.add")}
            </Button>
          </div>
          <div className="relative overflow-hidden">
            <div
              className={cn(
                "flex w-[200%] transition-transform duration-300",
                view === "list" ? "translate-x-0" : "-translate-x-1/2"
              )}
            >
              <div className="w-1/2 px-1">
                {loading ? (
                  <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Spinner size={18} /> {t("common.loading")}
                  </div>
                ) : error ? (
                  <p className="text-sm text-destructive">{error}</p>
                ) : repositories && repositories.length > 0 ? (
                  <div className="overflow-x-auto">
                    <table className="min-w-full text-sm">
                      <thead className="border-b text-muted-foreground">
                        <tr>
                          <th className="py-2 text-left">{t("repositories.columns.sourceMode")}</th>
                          <th className="py-2 text-left">{t("repositories.columns.remoteUrl")}</th>
                          <th className="py-2 text-left">{t("repositories.columns.scmType")}</th>
                          <th className="py-2 text-left">{t("repositories.columns.defaultBranch")}</th>
                          <th className="py-2 text-left">{t("repositories.columns.authMode")}</th>
                          <th className="py-2 text-right">{t("common.actions")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {repositories.map((repo) => (
                          <tr key={repo.repoId} className="border-b last:border-b-0">
                            <td className="py-2 uppercase tracking-tight">{repo.sourceMode}</td>
                            <td className="py-2 text-muted-foreground">{repo.remoteUrl ?? "—"}</td>
                            <td className="py-2 text-muted-foreground">{renderScmLabel(repo.scmType)}</td>
                            <td className="py-2 text-muted-foreground">{repo.defaultBranch ?? "—"}</td>
                            <td className="py-2 text-muted-foreground">
                              {renderAuthMode(repo.gitAuthMode, repo.gitAuthConfigured)}
                            </td>
                            <td className="py-2 text-right space-x-2">
                              <Button variant="ghost" size="sm" onClick={() => beginEdit(repo)}>
                                {t("repositories.actions.edit")}
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                className="text-destructive hover:text-destructive"
                                disabled={deleteMutation.isPending}
                                onClick={() => handleDeleteRepo(repo)}
                              >
                                {deleteMutation.isPending ? t("common.loading") : t("repositories.actions.delete")}
                              </Button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">{t("repositories.empty")}</p>
                )}
              </div>
              <div className="w-1/2 px-1">
                <form className="space-y-4" onSubmit={submitForm}>
                  <div>
                    <label className="block text-sm font-medium">{t("repositories.form.sourceMode")}</label>
                    <select
                      className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                      value={sourceMode}
                      onChange={(e) => setSourceMode(e.target.value as RepositorySourceMode)}
                    >
                      <option value="REMOTE">REMOTE</option>
                      <option value="UPLOAD">UPLOAD</option>
                      <option value="MIXED">MIXED</option>
                    </select>
                  </div>

                  {editingRepo ? (
                    <div className="rounded-md border border-dashed border-border bg-muted/40 p-3 text-sm text-muted-foreground">
                      {t("repositories.form.editing", {
                        name: editingRepo.remoteUrl ?? editingRepo.repoId,
                      })}
                    </div>
                  ) : null}

                  {needsRemoteFields ? (
                    <div className="space-y-3 rounded-lg border border-dashed border-border p-4">
                      <div>
                        <p className="text-sm font-medium">{t("repositories.form.remoteSection")}</p>
                        <p className="text-xs text-muted-foreground">{t("repositories.form.remoteHint")}</p>
                      </div>
                      <div className="grid gap-4 md:grid-cols-2">
                        <div className="md:col-span-2">
                          <label className="block text-sm font-medium">{t("repositories.form.remoteUrl")}</label>
                          <Input
                            className="mt-1"
                            value={remoteUrl}
                            placeholder="https://github.com/org/repo.git"
                            onChange={(e) => setRemoteUrl(e.target.value)}
                          />
                        </div>
                        <div>
                          <label className="block text-sm font-medium">{t("repositories.form.scmType")}</label>
                          <select
                            className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                            value={scmType}
                            onChange={(e) => setScmType(e.target.value as RepositoryScmType)}
                          >
                            {scmOptions.map((option) => (
                              <option key={option.value} value={option.value}>
                                {option.label}
                              </option>
                            ))}
                          </select>
                        </div>
                        <div>
                          <label className="block text-sm font-medium">
                            {t("repositories.columns.defaultBranch")}
                          </label>
                          <Input
                            className="mt-1"
                            value={defaultBranch}
                            placeholder="main"
                            onChange={(e) => setDefaultBranch(e.target.value)}
                          />
                        </div>
                        <div className="md:col-span-2">
                          <label className="block text-sm font-medium">{t("repositories.form.secretRef")}</label>
                          <Input
                            className="mt-1"
                            value={secretRef}
                            placeholder="vault://path/to/secret"
                            onChange={(e) => setSecretRef(e.target.value)}
                          />
                        </div>
                      </div>
                    </div>
                  ) : null}
                  {needsRemoteFields ? (
                    <div className="space-y-3 rounded-lg border border-dashed border-border p-4">
                      <div>
                        <p className="text-sm font-medium">{t("repositories.form.authSection")}</p>
                        <p className="text-xs text-muted-foreground">{t("repositories.form.authHint")}</p>
                      </div>
                      <div className="grid gap-4 md:grid-cols-2">
                        <div className="md:col-span-2">
                          <label className="block text-sm font-medium">{t("repositories.form.authMode")}</label>
                          <select
                            className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                            value={authMode}
                            onChange={(e) => setAuthMode(e.target.value as RepositoryGitAuthMode)}
                          >
                            <option value="NONE">{t("repositories.form.authModes.none")}</option>
                            <option value="BASIC">{t("repositories.form.authModes.basic")}</option>
                            <option value="TOKEN">{t("repositories.form.authModes.token")}</option>
                          </select>
                        </div>
                        {authMode !== "NONE" && editingRepo && authConfigured && authMode === initialAuthMode && !authReplace ? (
                          <div className="md:col-span-2 rounded-md bg-muted/40 p-3 text-xs text-muted-foreground flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                            <span>{t("repositories.form.authStored")}</span>
                            <Button type="button" variant="outline" size="sm" onClick={() => setAuthReplace(true)}>
                              {t("repositories.form.authReplace")}
                            </Button>
                          </div>
                        ) : null}
                        {authMode === "BASIC" ? (
                          <>
                            <div>
                              <label className="block text-sm font-medium">{t("repositories.form.authUsername")}</label>
                              <Input
                                className="mt-1"
                                value={authUsername}
                                disabled={!authInputsEnabled}
                                onChange={(e) => setAuthUsername(e.target.value)}
                              />
                            </div>
                            <div>
                              <label className="block text-sm font-medium">{t("repositories.form.authPassword")}</label>
                              <Input
                                className="mt-1"
                                type="password"
                                value={authPassword}
                                disabled={!authInputsEnabled}
                                onChange={(e) => setAuthPassword(e.target.value)}
                              />
                            </div>
                          </>
                        ) : null}
                        {authMode === "TOKEN" ? (
                          <>
                            <div>
                              <label className="block text-sm font-medium">{t("repositories.form.authToken")}</label>
                              <Input
                                className="mt-1"
                                type="password"
                                value={authToken}
                                disabled={!authInputsEnabled}
                                onChange={(e) => setAuthToken(e.target.value)}
                              />
                            </div>
                            <div>
                              <label className="block text-sm font-medium">{t("repositories.form.authTokenUser")}</label>
                              <Input
                                className="mt-1"
                                value={authUsername}
                                disabled={!authInputsEnabled}
                                onChange={(e) => setAuthUsername(e.target.value)}
                                placeholder={t("repositories.form.authTokenUserPlaceholder")}
                              />
                            </div>
                          </>
                        ) : null}
                      </div>
                    </div>
                  ) : null}

                  {needsUploadFields ? (
                    <div className="space-y-3 rounded-lg border border-dashed border-border p-4">
                      <div>
                        <p className="text-sm font-medium">{t("repositories.form.uploadSection")}</p>
                        <p className="text-xs text-muted-foreground">{t("repositories.form.uploadHint")}</p>
                      </div>
                      <div className="grid gap-4 md:grid-cols-2">
                        <div className="md:col-span-2">
                          <label className="block text-sm font-medium">{t("repositories.form.uploadKey")}</label>
                          <Input
                            className="mt-1"
                            value={uploadKey}
                            placeholder="s3://bucket/path/archive.zip"
                            onChange={(e) => setUploadKey(e.target.value)}
                          />
                        </div>
                        <div>
                          <label className="block text-sm font-medium">{t("repositories.form.uploadChecksum")}</label>
                          <Input
                            className="mt-1"
                            value={uploadChecksum}
                            onChange={(e) => setUploadChecksum(e.target.value)}
                          />
                        </div>
                        <div>
                          <label className="block text-sm font-medium">{t("repositories.form.uploadSize")}</label>
                          <Input
                            className="mt-1"
                            type="number"
                            min={0}
                            value={uploadSize}
                            onChange={(e) => setUploadSize(e.target.value)}
                          />
                        </div>
                        <div className="md:col-span-2 space-y-2">
                          <label className="block text-sm font-medium">{t("repositories.form.selectArchive")}</label>
                          <div className="flex flex-wrap items-center gap-2">
                            <input
                              ref={fileInputRef}
                              type="file"
                              accept=".zip,.tar,.tar.gz,.tgz"
                              className="hidden"
                              onChange={(event) => {
                                void handleArchiveSelect(event.target.files?.[0] ?? null)
                                if (event.target) {
                                  event.target.value = ""
                                }
                              }}
                            />
                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              disabled={digestingFile}
                              onClick={() => fileInputRef.current?.click()}
                            >
                              {digestingFile ? (
                                <span className="flex items-center gap-2">
                                  <Spinner size={14} />
                                  {t("repositories.form.digesting")}
                                </span>
                              ) : (
                                t("repositories.form.selectArchive")
                              )}
                            </Button>
                            {uploadFileLabel ? (
                              <span className="text-xs text-muted-foreground">
                                {t("repositories.form.fileSelected", {
                                  name: uploadFileLabel,
                                  size: uploadSizeReadable ?? "—",
                                })}
                              </span>
                            ) : null}
                          </div>
                          <p className="text-xs text-muted-foreground">{t("repositories.form.uploadHelper")}</p>
                        </div>
                      </div>
                    </div>
                  ) : null}

                  {serverError ? <p className="text-sm text-destructive">{serverError}</p> : null}
                  <DialogFooter className="flex-col gap-2 sm:flex-row sm:justify-end">
                    <Button type="button" variant="ghost" disabled={isSaving} onClick={() => resetForm("list")}>
                      {t("repositories.actions.back")}
                    </Button>
                    <Button type="submit" disabled={submitDisabled}>
                      {isSaving ? (
                        <span className="flex items-center gap-2">
                          <Spinner size={14} />
                          {t("common.saving")}
                        </span>
                      ) : editingRepo ? (
                        t("repositories.actions.update")
                      ) : (
                        t("repositories.form.save")
                      )}
                    </Button>
                  </DialogFooter>
                </form>
              </div>
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}

function formatBytes(size: number) {
  if (!Number.isFinite(size) || size <= 0) {
    return "0 B"
  }
  const units = ["B", "KB", "MB", "GB", "TB"]
  let value = size
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  const precision = unitIndex === 0 ? 0 : 1
  return `${value.toFixed(precision)} ${units[unitIndex]}`
}
