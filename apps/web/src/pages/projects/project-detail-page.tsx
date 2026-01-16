import { Link, useParams } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { ProjectOverviewCards } from "@/components/projects/project-overview-cards"
import { ProjectRepositoriesCard } from "@/components/projects/project-repositories-card"
import { ProjectTasksCard } from "@/components/projects/project-tasks-card"

import { getProjectOverview } from "@/services/project-service"
import type { ProjectOverviewResponse } from "@/types/api"
import { formatDateTime } from "@/lib/utils"

export function ProjectDetailPage() {
  const { t } = useTranslation()
  const { projectId } = useParams<{ projectId: string }>()

  const overviewQuery = useQuery<ProjectOverviewResponse>({
    queryKey: ["project", projectId, "overview"],
    queryFn: () => getProjectOverview(projectId!),
    enabled: Boolean(projectId),
    staleTime: 15_000,
  })

  if (!projectId) {
    return <p className="text-sm text-destructive">{t("projects.detail.errors.invalidProject")}</p>
  }

  if (overviewQuery.isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Spinner size={18} /> {t("common.loading")}
      </div>
    )
  }

  if (overviewQuery.isError || !overviewQuery.data) {
    return <p className="text-sm text-destructive">{t("projects.detail.errors.overview")}</p>
  }

  const project = overviewQuery.data.project
  const codeOwnersText = project.codeOwners.length > 0 ? project.codeOwners.join(", ") : "—"

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
        <div className="space-y-1">
          <Button asChild variant="ghost" size="sm">
            <Link to="/projects">{t("common.back")}</Link>
          </Button>
          <h1 className="text-2xl font-semibold">{project.name}</h1>
          <div className="text-sm text-muted-foreground">
            <span>{t("projects.detail.labels.codeOwners")}: {codeOwnersText}</span>
            <span className="mx-2">·</span>
            <span>{t("projects.detail.labels.createdAt")}: {formatDateTime(project.createdAt)}</span>
            {project.updatedAt ? (
              <>
                <span className="mx-2">·</span>
                <span>{t("projects.detail.labels.updatedAt")}: {formatDateTime(project.updatedAt)}</span>
              </>
            ) : null}
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="outline" size="sm">
            <Link to={`/findings?scope=PROJECT&projectId=${projectId}`}>{t("projects.detail.actions.viewFindings")}</Link>
          </Button>
          <Button asChild variant="outline" size="sm">
            <Link to={`/sca?projectId=${projectId}`}>{t("projects.detail.actions.viewSca")}</Link>
          </Button>
        </div>
      </div>

      <ProjectOverviewCards overview={overviewQuery.data} />

      <div className="grid gap-4 lg:grid-cols-2">
        <ProjectRepositoriesCard projectId={projectId} />
        <ProjectTasksCard projectId={projectId} />
      </div>
    </div>
  )
}

