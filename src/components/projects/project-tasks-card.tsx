import { useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { Link } from "react-router-dom"
import { useQuery } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { StatusBadge, TaskTypeBadge } from "@/components/status-badges"
import { formatDateTime } from "@/lib/utils"

import { listTasks } from "@/services/task-service"
import type { PageResponse, TaskSummary } from "@/types/api"

export function ProjectTasksCard({ projectId }: { projectId: string }) {
  const { t } = useTranslation()
  const [page, setPage] = useState({ limit: 10, offset: 0 })

  const tasksQuery = useQuery<PageResponse<TaskSummary>>({
    queryKey: ["project", projectId, "tasks", page],
    queryFn: () => listTasks({ projectId, limit: page.limit, offset: page.offset }),
    enabled: Boolean(projectId),
    staleTime: 10_000,
  })

  const tasks = tasksQuery.data?.items ?? []
  const total = tasksQuery.data?.total ?? 0

  const pageIndex = total === 0 ? 0 : Math.floor(page.offset / Math.max(1, page.limit))
  const pageCount = Math.max(1, Math.ceil(Math.max(1, total) / Math.max(1, page.limit)))
  const canPrev = page.offset > 0
  const canNext = page.offset + page.limit < total

  const rangeText = useMemo(() => {
    if (total === 0) return t("projects.detail.tasks.pageEmpty")
    const start = page.offset + 1
    const end = Math.min(total, page.offset + tasks.length)
    return t("projects.detail.tasks.pageRange", { start, end, total })
  }, [page.offset, tasks.length, t, total])

  const changePage = (direction: "prev" | "next") => {
    if (direction === "prev" && !canPrev) return
    if (direction === "next" && !canNext) return
    const nextOffset =
      direction === "prev"
        ? Math.max(0, page.offset - page.limit)
        : Math.min(total, page.offset + page.limit)
    setPage({ ...page, offset: nextOffset })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between gap-2 text-base">
          <span>{t("projects.detail.sections.tasks")}</span>
          <Button asChild variant="outline" size="sm">
            <Link to="/tasks/explorer">{t("projects.detail.actions.openTaskExplorer")}</Link>
          </Button>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {tasksQuery.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} />
            {t("common.loading")}
          </div>
        ) : tasksQuery.isError ? (
          <p className="text-sm text-destructive">{t("projects.detail.errors.tasks")}</p>
        ) : tasks.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("projects.detail.empty.tasks")}</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="border-b text-muted-foreground">
                <tr>
                  <th className="py-2 text-left">{t("projects.detail.columns.taskName")}</th>
                  <th className="py-2 text-left">{t("projects.detail.columns.taskType")}</th>
                  <th className="py-2 text-left">{t("projects.detail.columns.taskStatus")}</th>
                  <th className="py-2 text-left">{t("projects.detail.columns.createdAt")}</th>
                </tr>
              </thead>
              <tbody>
                {tasks.map((task) => (
                  <tr key={task.taskId} className="border-b last:border-b-0">
                    <td className="py-3 font-medium">{task.name ?? t("common.notAvailable")}</td>
                    <td className="py-3">
                      <TaskTypeBadge type={task.type} />
                    </td>
                    <td className="py-3">
                      <StatusBadge status={task.status} />
                    </td>
                    <td className="py-3 text-muted-foreground">{formatDateTime(task.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-xs text-muted-foreground">
          <span>{rangeText}</span>
          <div className="flex items-center gap-2">
            <span>{t("common.pageStatus", { page: total === 0 ? 0 : pageIndex + 1, total: pageCount })}</span>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => changePage("prev")}
              disabled={!canPrev || tasksQuery.isFetching}
            >
              {t("common.previous")}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => changePage("next")}
              disabled={!canNext || tasksQuery.isFetching}
            >
              {t("common.next")}
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

