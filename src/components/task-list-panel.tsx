import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import type { UseQueryResult } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import { useProjectsQuery } from "@/hooks/queries/use-projects"
import type { PageResponse, TaskListFilters, TaskSummary } from "@/types/api"
import { formatDateTime } from "@/lib/utils"
import { StatusBadge, TaskTypeBadge } from "@/components/status-badges"
import { cn } from "@/lib/utils"

type TaskListPanelProps = {
  tasksQuery: UseQueryResult<PageResponse<TaskSummary>>
  filters: TaskListFilters
  onFiltersChange: (filters: TaskListFilters) => void
  selectedTaskId: string | null
  onSelectTask: (taskId: string) => void
  titleKey?: string
}

const TASK_STATUS_OPTIONS = ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED"]

export function TaskListPanel({
  tasksQuery,
  filters,
  onFiltersChange,
  selectedTaskId,
  onSelectTask,
  titleKey = "tasks.title",
}: TaskListPanelProps) {
  const { t } = useTranslation()
  const projectsQuery = useProjectsQuery()
  const projectNameById = useMemo(() => {
    const map = new Map<string, string>()
    for (const project of projectsQuery.data ?? []) {
      map.set(project.projectId, project.name)
    }
    return map
  }, [projectsQuery.data])
  const [searchValue, setSearchValue] = useState(filters.search ?? "")

  useEffect(() => {
    setSearchValue(filters.search ?? "")
  }, [filters.search])

  const applyFilters = (next: Partial<TaskListFilters>) => {
    onFiltersChange({
      ...filters,
      ...next,
      offset: 0,
    })
  }

  const pageLimit = filters.limit ?? 25
  const pageOffset = filters.offset ?? 0
  const totalTasks = tasksQuery.data?.total ?? 0
  const totalPages = Math.max(1, Math.ceil((totalTasks || 1) / pageLimit))
  const currentPage = Math.min(totalPages - 1, Math.floor(pageOffset / pageLimit))
  const canPrev = pageOffset > 0
  const canNext = pageOffset + pageLimit < totalTasks

  const changePage = (direction: "prev" | "next") => {
    if (direction === "prev" && !canPrev) return
    if (direction === "next" && !canNext) return
    const nextOffset =
      direction === "prev" ? Math.max(0, pageOffset - pageLimit) : Math.min(totalTasks, pageOffset + pageLimit)
    applyFilters({ offset: nextOffset })
  }

  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle className="text-base">{t(titleKey)}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault()
            applyFilters({ search: searchValue.trim() || undefined })
          }}
        >
          <Input
            placeholder={t("tasks.filters.searchPlaceholder")}
            value={searchValue}
            onChange={(event) => setSearchValue(event.target.value)}
          />
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("projects.columns.name")}</label>
              <select
                className="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                value={filters.projectId ?? ""}
                onChange={(event) => applyFilters({ projectId: event.target.value || undefined })}
              >
                <option value="">{t("projects.tableTitle")}</option>
                {projectsQuery.data?.map((project) => (
                  <option key={project.projectId} value={project.projectId}>
                    {project.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tasks.details.status")}</label>
              <select
                className="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                value={filters.status ?? ""}
                onChange={(event) => applyFilters({ status: (event.target.value || undefined) as TaskListFilters["status"] })}
              >
                <option value="">{t("common.all")}</option>
                {TASK_STATUS_OPTIONS.map((status) => (
                  <option key={status} value={status}>
                    {t(`statuses.${status}`, { defaultValue: status })}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <Button type="submit" className="w-full">
            {t("common.search")}
          </Button>
        </form>

        <TaskList
          isLoading={tasksQuery.isLoading}
          error={tasksQuery.isError ? tasksQuery.error : null}
          tasks={tasksQuery.data?.items ?? []}
          projectNameById={projectNameById}
          selectedTaskId={selectedTaskId}
          onSelectTask={onSelectTask}
        />
        <div className="flex items-center justify-between border-t pt-3 text-xs text-muted-foreground">
          <span>{t("common.pageStatus", { page: totalTasks === 0 ? 0 : currentPage + 1, total: totalPages })}</span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => changePage("prev")} disabled={!canPrev || tasksQuery.isFetching}>
              {t("common.previous")}
            </Button>
            <Button variant="outline" size="sm" onClick={() => changePage("next")} disabled={!canNext || tasksQuery.isFetching}>
              {t("common.next")}
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

type TaskListProps = {
  isLoading: boolean
  error: unknown
  tasks: TaskSummary[]
  projectNameById: Map<string, string>
  selectedTaskId: string | null
  onSelectTask: (taskId: string) => void
}

function TaskList({ isLoading, error, tasks, projectNameById, selectedTaskId, onSelectTask }: TaskListProps) {
  const { t } = useTranslation()
  if (isLoading) {
    return <LoadingTasksPlaceholder />
  }

  if (error) {
    return <p className="text-sm text-destructive">{t("tasks.listError")}</p>
  }

  if (tasks.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("tasks.listEmpty")}</p>
  }

  return (
    <div className="space-y-2 overflow-y-auto">
      {tasks.map((task) => {
        const projectName = projectNameById.get(task.projectId) ?? t("common.notAvailable")
        const taskName = task.name ?? t("common.notAvailable")
        const typeLabel = t(`taskTypes.${task.type}`, { defaultValue: task.type })
        return (
          <button
            type="button"
            key={task.taskId}
            onClick={() => onSelectTask(task.taskId)}
            className={cn(
              "w-full rounded-md border px-3 py-2 text-left transition hover:bg-muted",
              task.taskId === selectedTaskId ? "border-primary bg-muted" : "border-border"
            )}
          >
            <div className="flex items-center justify-between gap-2">
              <div className="min-w-0">
                <p className="truncate text-sm font-semibold">{taskName}</p>
                <p className="truncate text-xs text-muted-foreground">{projectName}</p>
                <p className="text-xs text-muted-foreground">{formatDateTime(task.createdAt)}</p>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-1">
                <StatusBadge status={task.status} />
                <TaskTypeBadge type={task.type} />
              </div>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              {typeLabel}
              {task.engine ? ` • ${task.engine}` : ""}
            </p>
          </button>
        )
      })}
    </div>
  )
}

function LoadingTasksPlaceholder() {
  return (
    <div className="space-y-2">
      {Array.from({ length: 5 }).map((_, index) => (
        <div key={index} className="h-14 w-full animate-pulse rounded-md border border-dashed border-muted bg-muted/50" />
      ))}
    </div>
  )
}
