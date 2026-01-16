import { useMemo } from "react"
import { useNavigate, useParams } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useQuery } from "@tanstack/react-query"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Spinner } from "@/components/ui/spinner"
import { StatusBadge } from "@/components/status-badges"
import { MarkdownView } from "@/components/markdown/markdown-view"
import { getTicket } from "@/services/ticket-service"
import type { TicketSummary } from "@/types/api"

export function TicketDetailPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const { ticketId } = useParams<{ ticketId: string }>()
  const uiLang: "zh" | "en" = i18n.language?.startsWith("zh") ? "zh" : "en"

  const ticketQuery = useQuery<TicketSummary | undefined>({
    queryKey: ["ticket", ticketId],
    queryFn: () => getTicket(ticketId!),
    enabled: Boolean(ticketId),
  })

  const ticket = ticketQuery.data
  const payload = (ticket?.payload ?? {}) as Record<string, unknown>

  const title = useMemo(() => {
    const titleI18n = pickI18nString(payload["titleI18n"], uiLang)
    if (titleI18n) return titleI18n
    const summary = typeof payload["summary"] === "string" ? (payload["summary"] as string) : null
    return summary?.trim() || null
  }, [payload, uiLang])

  const description = useMemo(() => {
    const descI18n = pickI18nString(payload["descriptionI18n"], uiLang)
    if (descI18n) return descI18n
    const desc = typeof payload["description"] === "string" ? (payload["description"] as string) : null
    return desc?.trim() || null
  }, [payload, uiLang])

  const findingIds = useMemo(() => {
    const raw = payload["findingIds"]
    if (!Array.isArray(raw)) return []
    return raw.filter((it) => typeof it === "string") as string[]
  }, [payload])

  const scaIssueIds = useMemo(() => {
    const raw = payload["scaIssueIds"]
    if (!Array.isArray(raw)) return []
    return raw.filter((it) => typeof it === "string") as string[]
  }, [payload])

  if (ticketQuery.isLoading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center text-muted-foreground">
        <Spinner size={20} />
      </div>
    )
  }

  if (ticketQuery.isError || !ticket) {
    return (
      <div className="space-y-3">
        <Button variant="ghost" onClick={() => navigate(-1)}>
          {t("common.back")}
        </Button>
        <p className="text-sm text-destructive">{t("tickets.error")}</p>
      </div>
    )
  }

  return (
    <div className="min-w-0 space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
              {t("common.back")}
            </Button>
            <StatusBadge status={ticket.status} />
            <Badge variant="outline">{ticket.provider}</Badge>
          </div>
          <h1 className="mt-2 break-all text-2xl font-semibold">
            {t("tickets.detail.title", { defaultValue: "Ticket details" })} · {ticket.externalKey}
          </h1>
          <p className="break-all text-sm text-muted-foreground">{ticket.ticketId}</p>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-[320px,1fr]">
        <Card className="h-full">
          <CardHeader>
            <CardTitle className="text-base">{t("tickets.detail.info", { defaultValue: "Details" })}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div className="space-y-1">
              <div className="text-xs font-medium text-muted-foreground">{t("tickets.columns.project")}</div>
              <div className="break-all font-mono text-xs">{ticket.projectId}</div>
            </div>
            <div className="space-y-1">
              <div className="text-xs font-medium text-muted-foreground">{t("tickets.columns.createdAt")}</div>
              <div className="text-xs text-muted-foreground">{new Date(ticket.createdAt).toLocaleString()}</div>
            </div>
            {ticket.updatedAt ? (
              <div className="space-y-1">
                <div className="text-xs font-medium text-muted-foreground">{t("common.updatedAt", { defaultValue: "Updated at" })}</div>
                <div className="text-xs text-muted-foreground">{new Date(ticket.updatedAt).toLocaleString()}</div>
              </div>
            ) : null}

            <div className="space-y-2 pt-2">
              <div className="flex items-center justify-between gap-2">
                <div className="text-xs font-medium text-muted-foreground">
                  {t("tickets.detail.findings", { defaultValue: "Findings" })} · {findingIds.length}
                </div>
                <Button size="sm" variant="outline" onClick={() => navigate("/findings")}>
                  {t("tickets.draft.goFindings", { defaultValue: "Go to findings" })}
                </Button>
              </div>
              {findingIds.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("common.notAvailable")}</p>
              ) : (
                <div className="max-h-[320px] space-y-1 overflow-auto">
                  {findingIds.map((id) => (
                    <button
                      key={id}
                      type="button"
                      className="block w-full text-left font-mono text-xs underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-muted-foreground"
                      onClick={() => navigate(`/findings/${id}`)}
                    >
                      {id}
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div className="space-y-2 pt-2">
              <div className="flex items-center justify-between gap-2">
                <div className="text-xs font-medium text-muted-foreground">
                  {t("tickets.detail.scaIssues", { defaultValue: "SCA issues" })} · {scaIssueIds.length}
                </div>
                <Button size="sm" variant="outline" onClick={() => navigate("/sca")}>
                  {t("tickets.detail.goSca", { defaultValue: "Go to SCA" })}
                </Button>
              </div>
              {scaIssueIds.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("common.notAvailable")}</p>
              ) : (
                <div className="max-h-[320px] space-y-1 overflow-auto">
                  {scaIssueIds.map((id) => (
                    <button
                      key={id}
                      type="button"
                      className="block w-full text-left font-mono text-xs underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-muted-foreground"
                      onClick={() => navigate(`/sca/issues/${id}`)}
                    >
                      {id}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </CardContent>
        </Card>

        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("tickets.detail.summary", { defaultValue: "Summary" })}</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="break-words text-sm">{title ?? "—"}</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t("tickets.detail.description", { defaultValue: "Description" })}</CardTitle>
            </CardHeader>
            <CardContent className="min-w-0">
              <MarkdownView content={description} />
            </CardContent>
          </Card>

          <details className="rounded-md border bg-muted/20 p-3">
            <summary className="cursor-pointer text-sm font-medium text-muted-foreground">
              {t("tickets.detail.raw", { defaultValue: "Raw payload" })}
            </summary>
            <pre className="mt-2 max-h-[360px] overflow-auto whitespace-pre-wrap break-words text-xs text-muted-foreground">
              {JSON.stringify(ticket.payload, null, 2)}
            </pre>
          </details>
        </div>
      </div>
    </div>
  )
}

function pickI18nString(value: unknown, lang: "zh" | "en"): string | null {
  if (!value || typeof value !== "object") return null
  const obj = value as Record<string, unknown>
  const primary = obj[lang]
  if (typeof primary === "string" && primary.trim()) return primary
  const fallbackKey = lang === "zh" ? "en" : "zh"
  const fallback = obj[fallbackKey]
  if (typeof fallback === "string" && fallback.trim()) return fallback
  return null
}
