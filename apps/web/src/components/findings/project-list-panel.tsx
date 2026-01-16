import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import type { UseQueryResult } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Spinner } from "@/components/ui/spinner"
import { cn } from "@/lib/utils"
import type { Project } from "@/types/api"

type Props = {
  projectsQuery: UseQueryResult<Project[]>
  selectedProjectId: string | null
  onSelectProject: (projectId: string) => void
  titleKey?: string
}

export function ProjectListPanel({
  projectsQuery,
  selectedProjectId,
  onSelectProject,
  titleKey = "projects.title",
}: Props) {
  const { t } = useTranslation()
  const [searchValue, setSearchValue] = useState("")

  const projects = useMemo(() => projectsQuery.data ?? [], [projectsQuery.data])

  useEffect(() => {
    setSearchValue("")
  }, [titleKey])

  const filtered = useMemo(() => {
    const term = searchValue.trim().toLowerCase()
    if (!term) return projects
    return projects.filter((p) => p.name.toLowerCase().includes(term))
  }, [projects, searchValue])

  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle className="text-base">{t(titleKey)}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <Input
          placeholder={t("projects.searchPlaceholder", { defaultValue: "Search projects" })}
          value={searchValue}
          onChange={(event) => setSearchValue(event.target.value)}
        />
        {projectsQuery.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} /> {t("common.loading")}
          </div>
        ) : projectsQuery.isError ? (
          <p className="text-sm text-destructive">{t("projects.error", { defaultValue: "项目列表加载失败。" })}</p>
        ) : filtered.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("projects.empty")}</p>
        ) : (
          <div className="space-y-2 overflow-y-auto">
            {filtered.map((project) => (
              <button
                type="button"
                key={project.projectId}
                onClick={() => onSelectProject(project.projectId)}
                className={cn(
                  "w-full rounded-md border px-3 py-2 text-left transition hover:bg-muted",
                  project.projectId === selectedProjectId ? "border-primary bg-muted" : "border-border"
                )}
              >
                <p className="truncate text-sm font-semibold">{project.name}</p>
                <p className="mt-1 text-xs text-muted-foreground">
                  {t("projects.codeOwners", { defaultValue: "Code owners" })}: {project.codeOwners?.length ?? 0}
                </p>
              </button>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

