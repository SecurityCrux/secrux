import { useMemo } from "react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { Badge } from "@/components/ui/badge"

import { listRepositories } from "@/services/project-service"
import type { RepositoryResponse } from "@/types/api"

function formatRepositoryLabel(repo: RepositoryResponse) {
  if (repo.remoteUrl) return repo.remoteUrl
  if (repo.uploadKey) return `upload:${repo.uploadKey}`
  return "—"
}

export function ProjectRepositoriesCard({ projectId }: { projectId: string }) {
  const { t } = useTranslation()

  const repositoriesQuery = useQuery<RepositoryResponse[]>({
    queryKey: ["project", projectId, "repositories"],
    queryFn: () => listRepositories(projectId),
    enabled: Boolean(projectId),
    staleTime: 30_000,
  })

  const repositories = repositoriesQuery.data ?? []

  const repoRows = useMemo(() => {
    return repositories.map((repo) => ({
      repo,
      label: formatRepositoryLabel(repo),
    }))
  }, [repositories])

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between gap-2 text-base">
          <span>{t("projects.detail.sections.repositories")}</span>
          <Button asChild variant="outline" size="sm">
            <Link to={`/findings?scope=PROJECT&projectId=${projectId}`}>{t("projects.detail.actions.viewFindings")}</Link>
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent>
        {repositoriesQuery.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} />
            {t("common.loading")}
          </div>
        ) : repositoriesQuery.isError ? (
          <p className="text-sm text-destructive">{t("projects.detail.errors.repositories")}</p>
        ) : repoRows.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("projects.detail.empty.repositories")}</p>
        ) : (
          <div className="max-h-[420px] space-y-2 overflow-y-auto pr-1">
            {repoRows.map(({ repo, label }) => (
              <div key={repo.repoId} className="flex items-start justify-between gap-3 rounded-md border px-3 py-2">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold">{label}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <Badge variant="secondary" className="font-mono text-[10px]">
                      {repo.sourceMode}
                    </Badge>
                    {repo.scmType ? (
                      <span className="font-mono text-[10px]">{repo.scmType}</span>
                    ) : null}
                    {repo.defaultBranch ? <span>{t("projects.detail.labels.branch")}: {repo.defaultBranch}</span> : null}
                  </div>
                </div>
                <div className="flex shrink-0 flex-col items-end gap-2">
                  <Button asChild variant="outline" size="sm">
                    <Link to={`/findings?scope=REPO&projectId=${projectId}&repoId=${repo.repoId}`}>
                      {t("projects.detail.actions.viewRepoFindings")}
                    </Link>
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

