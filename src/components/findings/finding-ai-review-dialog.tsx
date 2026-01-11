import { useEffect } from "react"
import { useTranslation } from "react-i18next"
import { useQuery } from "@tanstack/react-query"

import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Spinner } from "@/components/ui/spinner"
import { Button } from "@/components/ui/button"
import { getAiJob } from "@/services/ai-job-service"
import type { AiJobTicket } from "@/types/api"

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  jobId: string | null
  submitError: string | null
  onCompleted?: () => void
}

export function FindingAiReviewDialog({ open, onOpenChange, jobId, submitError, onCompleted }: Props) {
  const { t, i18n } = useTranslation()

  const aiJobQuery = useQuery<AiJobTicket>({
    queryKey: ["ai-job", jobId],
    queryFn: () => getAiJob(jobId!),
    enabled: open && Boolean(jobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "QUEUED" || status === "RUNNING" ? 1500 : false
    },
  })

  useEffect(() => {
    if (aiJobQuery.data?.status === "COMPLETED") {
      onCompleted?.()
    }
  }, [aiJobQuery.data?.status, onCompleted])

  const aiResult = aiJobQuery.data?.result ?? null
  const aiResultVerdict =
    aiResult && typeof aiResult === "object" && typeof aiResult["verdict"] === "string" ? (aiResult["verdict"] as string) : null
  const uiLang: "zh" | "en" = i18n.language?.startsWith("zh") ? "zh" : "en"
  const aiOpinion =
    aiResult && typeof aiResult === "object" && aiResult["opinionI18n"] && typeof aiResult["opinionI18n"] === "object"
      ? (aiResult["opinionI18n"] as Record<string, unknown>)
      : null
  const aiOpinionText =
    (aiOpinion && aiOpinion[uiLang] && typeof aiOpinion[uiLang] === "object" ? (aiOpinion[uiLang] as Record<string, unknown>) : null) ??
    (aiOpinion && aiOpinion["en"] && typeof aiOpinion["en"] === "object" ? (aiOpinion["en"] as Record<string, unknown>) : null) ??
    (aiOpinion && aiOpinion["zh"] && typeof aiOpinion["zh"] === "object" ? (aiOpinion["zh"] as Record<string, unknown>) : null)
  const aiResultSummary =
    aiOpinionText && typeof aiOpinionText["summary"] === "string"
      ? (aiOpinionText["summary"] as string)
      : aiResult && typeof aiResult === "object" && typeof aiResult["summary"] === "string"
        ? (aiResult["summary"] as string)
        : null
  const aiResultFixHint =
    aiOpinionText && typeof aiOpinionText["fixHint"] === "string"
      ? (aiOpinionText["fixHint"] as string)
      : aiResult && typeof aiResult === "object" && typeof aiResult["fixHint"] === "string"
        ? (aiResult["fixHint"] as string)
        : null
  const aiRecommendation =
    aiResult && typeof aiResult === "object" && aiResult["recommendation"] && typeof aiResult["recommendation"] === "object"
      ? (aiResult["recommendation"] as Record<string, unknown>)
      : null
  const aiFindings = aiRecommendation && Array.isArray(aiRecommendation["findings"]) ? (aiRecommendation["findings"] as unknown[]) : []

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        onOpenChange(nextOpen)
      }}
    >
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("findings.aiReviewDialogTitle", { defaultValue: "AI 复核" })}</DialogTitle>
        </DialogHeader>
        {submitError ? (
          <p className="text-sm text-destructive">{submitError}</p>
        ) : !jobId ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={18} /> {t("findings.aiReviewSubmitting", { defaultValue: "正在提交..." })}
          </div>
        ) : aiJobQuery.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={18} /> {t("findings.aiReviewLoading", { defaultValue: "正在获取结果..." })}
          </div>
        ) : aiJobQuery.isError ? (
          <p className="text-sm text-destructive">{t("findings.aiReviewLoadFailed", { defaultValue: "获取 AI 复核状态失败" })}</p>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
              <div className="text-muted-foreground">
                {t("findings.aiReviewJobId", { defaultValue: "Job" })}: {jobId}
              </div>
              <div className="flex items-center gap-2">
                <span className="text-muted-foreground">
                  {t("findings.aiReviewStatus", { defaultValue: "状态" })}: {aiJobQuery.data?.status}
                </span>
                <Button type="button" size="sm" variant="outline" onClick={() => aiJobQuery.refetch()}>
                  {t("common.refresh", { defaultValue: "刷新" })}
                </Button>
              </div>
            </div>

            {aiJobQuery.data?.error ? <p className="text-sm text-destructive">{aiJobQuery.data.error}</p> : null}

            {aiResultSummary || aiResultVerdict ? (
              <div className="rounded-md border p-3 text-sm">
                <div>
                  {t("findings.aiReviewVerdict", { defaultValue: "结论" })}: {aiResultVerdict ?? "—"}
                </div>
                <div className="mt-1 text-muted-foreground">
                  {t("findings.aiReviewSummary", { defaultValue: "摘要" })}: {aiResultSummary ?? "—"}
                </div>
                {aiResultFixHint ? (
                  <div className="mt-1 text-muted-foreground">
                    {t("findings.aiReviewFixHint", { defaultValue: "修复建议" })}: {aiResultFixHint}
                  </div>
                ) : null}
              </div>
            ) : null}

            {aiFindings.length > 0 ? (
              <div className="space-y-2">
                <div className="text-sm font-medium">{t("findings.aiReviewFindings", { defaultValue: "复核要点" })}</div>
                <ul className="space-y-2 text-sm">
                  {aiFindings.slice(0, 6).map((finding, idx) => {
                    const obj = finding && typeof finding === "object" ? (finding as Record<string, unknown>) : null
                    const summary = obj && typeof obj["summary"] === "string" ? (obj["summary"] as string) : "—"
                    const severity = obj && typeof obj["severity"] === "string" ? (obj["severity"] as string) : "INFO"
                    return (
                      <li key={idx} className="rounded-md border p-3">
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-medium">{summary}</span>
                          <span className="text-xs text-muted-foreground">{severity}</span>
                        </div>
                      </li>
                    )
                  })}
                </ul>
              </div>
            ) : null}

            {aiResult ? (
              <details className="rounded-md border p-3">
                <summary className="cursor-pointer text-sm text-muted-foreground">
                  {t("findings.aiReviewRaw", { defaultValue: "原始结果" })}
                </summary>
                <pre className="mt-2 max-h-[320px] overflow-auto whitespace-pre-wrap text-xs">
                  {JSON.stringify(aiResult, null, 2)}
                </pre>
              </details>
            ) : null}
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
