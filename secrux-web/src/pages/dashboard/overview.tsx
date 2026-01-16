import { useTranslation } from "react-i18next"
import { useQuery } from "@tanstack/react-query"
import { useNavigate } from "react-router-dom"
import { useAuth } from "@/hooks/use-auth"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Spinner } from "@/components/ui/spinner"
import { listOverviewRecentTasks, listOverviewTopFindings, getOverviewSummary } from "@/services/overview-service"
import { SeverityBadge, StatusBadge } from "@/components/status-badges"
import { formatDateTime } from "@/lib/utils"
import type { OverviewFindingItem, OverviewTaskItem, PageResponse } from "@/types/api"

export function DashboardOverview() {
  const { user } = useAuth()
  const { t } = useTranslation()
  const navigate = useNavigate()

  const summaryQuery = useQuery({
    queryKey: ["overview", "summary"],
    queryFn: () => getOverviewSummary({ window: "7d" }),
    staleTime: 30_000,
    refetchInterval: 15_000,
  })

  const recentTasksQuery = useQuery<PageResponse<OverviewTaskItem>>({
    queryKey: ["overview", "tasks", "recent"],
    queryFn: () => listOverviewRecentTasks({ limit: 6, offset: 0 }),
    staleTime: 30_000,
    refetchInterval: 15_000,
  })

  const topFindingsQuery = useQuery<PageResponse<OverviewFindingItem>>({
    queryKey: ["overview", "findings", "top"],
    queryFn: () => listOverviewTopFindings({ limit: 6, offset: 0 }),
    staleTime: 30_000,
    refetchInterval: 15_000,
  })

  const stats = [
    {
      label: t("dashboard.stats.projects"),
      value: summaryQuery.isLoading ? "…" : summaryQuery.data?.projects ?? 0,
    },
    {
      label: t("dashboard.stats.repositories", { defaultValue: "Repositories" }),
      value: summaryQuery.isLoading ? "…" : summaryQuery.data?.repositories ?? 0,
    },
    {
      label: t("dashboard.stats.runningTasks", { defaultValue: "Running tasks" }),
      value: summaryQuery.isLoading ? "…" : summaryQuery.data?.tasks.running ?? 0,
    },
    {
      label: t("dashboard.stats.openFindings", { defaultValue: "Open findings" }),
      value: summaryQuery.isLoading ? "…" : summaryQuery.data?.findings.open ?? 0,
    },
    {
      label: t("dashboard.stats.executors", { defaultValue: "Executors" }),
      value: summaryQuery.isLoading ? "…" : summaryQuery.data?.executors.total ?? 0,
    },
  ]

  const recentTasks = recentTasksQuery.data?.items ?? []
  const topFindings = topFindingsQuery.data?.items ?? []

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>{t("dashboard.welcome", { name: user?.username ?? "user" })}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-muted-foreground">{t("dashboard.description")}</p>
        </CardContent>
      </Card>
      <div className="grid gap-4 md:grid-cols-3 xl:grid-cols-5">
        {stats.map((stat) => (
          <Card key={stat.label}>
            <CardHeader className="pb-2">
              <p className="text-sm text-muted-foreground">{stat.label}</p>
            </CardHeader>
            <CardContent>
              {summaryQuery.isLoading ? (
                <Spinner size={20} className="text-muted-foreground" />
              ) : (
                <p className="text-3xl font-semibold">{stat.value}</p>
              )}
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">
              {t("dashboard.sections.recentTasks", { defaultValue: "Recent tasks" })}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            {recentTasksQuery.isLoading ? (
              <div className="flex items-center gap-2 text-muted-foreground">
                <Spinner size={18} /> {t("common.loading")}
              </div>
            ) : recentTasks.length === 0 ? (
              <p className="text-muted-foreground">{t("dashboard.empty.recentTasks", { defaultValue: "No recent tasks." })}</p>
            ) : (
              <div className="space-y-2">
                {recentTasks.map((task) => (
                  <button
                    key={task.taskId}
                    type="button"
                    className="w-full rounded-md border p-3 text-left transition hover:bg-muted/40"
                    onClick={() => navigate("/tasks/explorer")}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="truncate font-medium">{task.name ?? t("common.notAvailable")}</div>
                      <StatusBadge status={task.status} />
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                      <span>{task.type}</span>
                      <span className="truncate">{formatDateTime(task.createdAt)}</span>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">
              {t("dashboard.sections.topFindings", { defaultValue: "Top findings" })}
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            {topFindingsQuery.isLoading ? (
              <div className="flex items-center gap-2 text-muted-foreground">
                <Spinner size={18} /> {t("common.loading")}
              </div>
            ) : topFindings.length === 0 ? (
              <p className="text-muted-foreground">{t("dashboard.empty.topFindings", { defaultValue: "No open findings." })}</p>
            ) : (
              <div className="space-y-2">
                {topFindings.map((finding) => (
                  <button
                    key={finding.findingId}
                    type="button"
                    className="w-full rounded-md border p-3 text-left transition hover:bg-muted/40"
                    onClick={() => navigate(`/findings/${finding.findingId}`)}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="truncate font-medium">{finding.ruleId ?? "—"}</div>
                      <SeverityBadge severity={finding.severity} />
                    </div>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                      <StatusBadge status={finding.status} />
                      {finding.introducedBy ? <span className="truncate">{finding.introducedBy}</span> : null}
                      <span className="truncate">{finding.findingId}</span>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
