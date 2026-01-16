import { useMemo } from "react"
import { useTranslation } from "react-i18next"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { SeverityBadge, TaskTypeBadge } from "@/components/status-badges"
import type { ProjectOverviewResponse, RepositorySourceMode, Severity, TaskType } from "@/types/api"

const SEVERITY_ORDER: Severity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"]
const REPO_MODE_ORDER: RepositorySourceMode[] = ["REMOTE", "UPLOAD", "MIXED"]
const TASK_TYPE_ORDER: TaskType[] = ["CODE_CHECK", "SCA_CHECK", "SUPPLY_CHAIN", "SECURITY_SCAN"]

export function ProjectOverviewCards({ overview }: { overview: ProjectOverviewResponse }) {
  const { t } = useTranslation()

  const repoRows = useMemo(() => {
    const entries = Object.entries(overview.repositories.bySourceMode) as Array<[RepositorySourceMode, number]>
    const map = new Map(entries)
    return REPO_MODE_ORDER.filter((mode) => map.has(mode)).map((mode) => ({ mode, count: map.get(mode) ?? 0 }))
  }, [overview.repositories.bySourceMode])

  const taskTypeRows = useMemo(() => {
    const entries = Object.entries(overview.tasks.byType) as Array<[TaskType, number]>
    const map = new Map(entries)
    return TASK_TYPE_ORDER.filter((type) => map.has(type)).map((type) => ({ type, count: map.get(type) ?? 0 }))
  }, [overview.tasks.byType])

  const findingSeverityRows = useMemo(() => {
    const entries = Object.entries(overview.findings.bySeverityUnresolved) as Array<[Severity, number]>
    const map = new Map(entries)
    return SEVERITY_ORDER.filter((sev) => map.has(sev)).map((sev) => ({ sev, count: map.get(sev) ?? 0 }))
  }, [overview.findings.bySeverityUnresolved])

  const scaSeverityRows = useMemo(() => {
    const entries = Object.entries(overview.sca.bySeverityUnresolved) as Array<[Severity, number]>
    const map = new Map(entries)
    return SEVERITY_ORDER.filter((sev) => map.has(sev)).map((sev) => ({ sev, count: map.get(sev) ?? 0 }))
  }, [overview.sca.bySeverityUnresolved])

  return (
    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("projects.detail.overview.repositories")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">{t("projects.detail.overview.total")}</span>
            <span className="font-semibold">{overview.repositories.total}</span>
          </div>
          {repoRows.length > 0 ? (
            <div className="space-y-1 text-xs text-muted-foreground">
              {repoRows.map((row) => (
                <div key={row.mode} className="flex items-center justify-between">
                  <span className="font-mono">{row.mode}</span>
                  <span>{row.count}</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">{t("common.notAvailable")}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("projects.detail.overview.tasks")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">{t("projects.detail.overview.total")}</span>
            <span className="font-semibold">{overview.tasks.total}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">{t("projects.detail.overview.running")}</span>
            <span className="font-semibold">{overview.tasks.running}</span>
          </div>
          {taskTypeRows.length > 0 ? (
            <div className="space-y-1 text-xs">
              {taskTypeRows.map((row) => (
                <div key={row.type} className="flex items-center justify-between gap-2">
                  <TaskTypeBadge type={row.type} />
                  <span className="text-muted-foreground">{row.count}</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">{t("common.notAvailable")}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("projects.detail.overview.findings")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">{t("projects.detail.overview.open")}</span>
            <span className="font-semibold">{overview.findings.open}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">{t("projects.detail.overview.confirmed")}</span>
            <span className="font-semibold">{overview.findings.confirmed}</span>
          </div>
          {findingSeverityRows.length > 0 ? (
            <div className="space-y-1 text-xs">
              {findingSeverityRows.map((row) => (
                <div key={row.sev} className="flex items-center justify-between gap-2">
                  <SeverityBadge severity={row.sev} />
                  <span className="text-muted-foreground">{row.count}</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">{t("common.notAvailable")}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("projects.detail.overview.sca")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">{t("projects.detail.overview.open")}</span>
            <span className="font-semibold">{overview.sca.open}</span>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">{t("projects.detail.overview.confirmed")}</span>
            <span className="font-semibold">{overview.sca.confirmed}</span>
          </div>
          {scaSeverityRows.length > 0 ? (
            <div className="space-y-1 text-xs">
              {scaSeverityRows.map((row) => (
                <div key={row.sev} className="flex items-center justify-between gap-2">
                  <SeverityBadge severity={row.sev} />
                  <span className="text-muted-foreground">{row.count}</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">{t("common.notAvailable")}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

