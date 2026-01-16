import { useEffect, useMemo, useState } from "react"
import { useNavigate } from "react-router-dom"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Checkbox } from "@/components/ui/checkbox"
import { Spinner } from "@/components/ui/spinner"
import { Textarea } from "@/components/ui/textarea"

import { useTicketsQuery } from "@/hooks/queries/use-tickets"
import { useProjectsQuery } from "@/hooks/queries/use-projects"
import { createTicketFromDraft, listTicketProviders, updateTicketStatus } from "@/services/ticket-service"
import {
  applyTicketDraftAi,
  clearTicketDraft,
  generateTicketDraftAi,
  getCurrentTicketDraft,
  polishTicketDraftAi,
  removeItemsFromTicketDraft,
  updateTicketDraft,
} from "@/services/ticket-draft-service"
import { getAiJob } from "@/services/ai-job-service"
import type { AiJobTicket, TicketDraftDetail, TicketDraftItem, TicketIssueType, TicketProviderTemplate, TicketStatus, TicketSummary } from "@/types/api"
import { SeverityBadge, StatusBadge } from "@/components/status-badges"

const TICKET_STATUS: TicketStatus[] = ["OPEN", "SYNCED", "FAILED"]

export function TicketManagementPage() {
  const { t } = useTranslation()
  const providersQuery = useQuery<TicketProviderTemplate[]>({
    queryKey: ["ticket-providers"],
    queryFn: () => listTicketProviders(),
    staleTime: 60_000,
  })
  const providerTemplates = providersQuery.data ?? []
  const projectsQuery = useProjectsQuery()
  const [filters, setFilters] = useState({
    projectId: "",
    provider: "",
    status: "",
    search: "",
  })
  const ticketsQuery = useTicketsQuery({
    projectId: filters.projectId || undefined,
    provider: filters.provider || undefined,
    status: filters.status || undefined,
    search: filters.search || undefined,
    limit: 50,
    offset: 0,
  })
  const [createOpen, setCreateOpen] = useState(false)
  const updateStatusMutation = useMutation({
    mutationFn: ({ ticketId, status }: { ticketId: string; status: TicketStatus }) =>
      updateTicketStatus(ticketId, { status }),
    onSuccess: () => {
      void ticketsQuery.refetch()
    },
  })

  const filteredProjects = projectsQuery.data ?? []

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("tickets.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("tickets.subtitle")}</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>{t("tickets.actions.create")}</Button>
      </div>
      <TicketFilters
        filters={filters}
        projects={filteredProjects}
        providers={providerTemplates}
        onChange={setFilters}
        onReset={() =>
          setFilters({
            projectId: "",
            provider: "",
            status: "",
            search: "",
          })
        }
      />
      <TicketTable
        tickets={ticketsQuery.data ?? []}
        isLoading={ticketsQuery.isLoading}
        isError={ticketsQuery.isError}
        onStatusChange={(ticketId, status) => updateStatusMutation.mutate({ ticketId, status })}
      />
      <TicketCreateDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        providers={providerTemplates}
        onCreated={() => {
          void ticketsQuery.refetch()
        }}
      />
    </div>
  )
}

type TicketFiltersProps = {
  filters: {
    projectId: string
    provider: string
    status: string
    search: string
  }
  projects: { projectId: string; name: string }[]
  providers: TicketProviderTemplate[]
  onChange: (next: TicketFiltersProps["filters"]) => void
  onReset: () => void
}

function TicketFilters({ filters, projects, providers, onChange, onReset }: TicketFiltersProps) {
  const { t } = useTranslation()
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("tickets.filters.title")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid gap-3 md:grid-cols-4">
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("tickets.filters.project")}</label>
            <select
              className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              value={filters.projectId}
              onChange={(event) => onChange({ ...filters, projectId: event.target.value })}
            >
              <option value="">{t("common.all")}</option>
              {projects.map((project) => (
                <option key={project.projectId} value={project.projectId}>
                  {project.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("tickets.filters.provider")}</label>
            {providers.length > 0 ? (
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={filters.provider}
                onChange={(event) => onChange({ ...filters, provider: event.target.value })}
              >
                <option value="">{t("common.all")}</option>
                {providers.map((p) => (
                  <option key={p.provider} value={p.provider}>
                    {p.name}
                  </option>
                ))}
              </select>
            ) : (
              <Input
                className="mt-1"
                value={filters.provider}
                onChange={(event) => onChange({ ...filters, provider: event.target.value })}
              />
            )}
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("tickets.filters.status")}</label>
            <select
              className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              value={filters.status}
              onChange={(event) => onChange({ ...filters, status: event.target.value })}
            >
              <option value="">{t("common.all")}</option>
              {TICKET_STATUS.map((status) => (
                <option key={status} value={status}>
                  {status}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("tickets.filters.search")}</label>
            <Input
              className="mt-1"
              placeholder={t("tickets.filters.searchPlaceholder")}
              value={filters.search}
              onChange={(event) => onChange({ ...filters, search: event.target.value })}
            />
          </div>
        </div>
        <div className="flex justify-end">
          <Button variant="ghost" size="sm" onClick={onReset}>
            {t("tickets.actions.reset")}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

type TicketTableProps = {
  tickets: TicketSummary[]
  isLoading: boolean
  isError: boolean
  onStatusChange: (ticketId: string, status: TicketStatus) => void
}

function TicketTable({ tickets, isLoading, isError, onStatusChange }: TicketTableProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  if (isLoading) {
    return (
      <Card>
        <CardContent className="py-8 text-sm text-muted-foreground">
          <Spinner size={18} /> {t("common.loading")}
        </CardContent>
      </Card>
    )
  }
  if (isError) {
    return (
      <Card>
            <CardContent className="py-8 text-sm text-destructive">{t("tickets.error")}</CardContent>
      </Card>
    )
  }
  if (tickets.length === 0) {
    return (
      <Card>
        <CardContent className="py-8 text-sm text-muted-foreground">{t("tickets.empty")}</CardContent>
      </Card>
    )
  }
  return (
    <Card>
      <CardContent className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead className="border-b text-muted-foreground">
            <tr>
              <th className="py-2 text-left">{t("tickets.columns.key")}</th>
              <th className="py-2 text-left">{t("tickets.columns.provider")}</th>
              <th className="py-2 text-left">{t("tickets.columns.project")}</th>
              <th className="py-2 text-left">{t("tickets.columns.status")}</th>
              <th className="py-2 text-left">{t("tickets.columns.createdAt")}</th>
              <th className="py-2 text-left">{t("tickets.columns.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {tickets.map((ticket) => (
              <tr key={ticket.ticketId} className="border-b last:border-b-0">
                <td className="py-2 font-mono text-xs">
                  <button
                    type="button"
                    className="text-left underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-muted-foreground"
                    onClick={() => navigate(`/tickets/${ticket.ticketId}`)}
                  >
                    {ticket.externalKey}
                  </button>
                </td>
                <td className="py-2">{ticket.provider}</td>
                <td className="py-2 font-mono text-xs">{ticket.projectId}</td>
                <td className="py-2">
                  <StatusBadge status={ticket.status} />
                </td>
                <td className="py-2 text-muted-foreground">{new Date(ticket.createdAt).toLocaleString()}</td>
                <td className="py-2">
                  <select
                    className="h-8 rounded-md border border-input bg-background px-2 text-xs"
                    value={ticket.status}
                    onChange={(event) => onStatusChange(ticket.ticketId, event.target.value as TicketStatus)}
                  >
                    {TICKET_STATUS.map((status) => (
                      <option key={status} value={status}>
                        {status}
                      </option>
                    ))}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </CardContent>
    </Card>
  )
}

type TicketCreateDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  providers: TicketProviderTemplate[]
  onCreated: () => void
}

function TicketCreateDialog({ open, onOpenChange, providers, onCreated }: TicketCreateDialogProps) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const uiLang: "zh" | "en" = i18n.language?.startsWith("zh") ? "zh" : "en"

  const draftQuery = useQuery<TicketDraftDetail | null>({
    queryKey: ["ticket-draft", "current"],
    queryFn: () => getCurrentTicketDraft(),
    enabled: open,
    staleTime: 0,
  })
  const draft = draftQuery.data
  const draftItems = draft?.items ?? []
  const draftCount = draft?.itemCount ?? draftItems.length

  const [provider, setProvider] = useState("")
  const [issueType, setIssueType] = useState<TicketIssueType>("BUG")
  const [clearDraftAfter, setClearDraftAfter] = useState(true)
  const [titleEn, setTitleEn] = useState("")
  const [titleZh, setTitleZh] = useState("")
  const [descEn, setDescEn] = useState("")
  const [descZh, setDescZh] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [aiJobId, setAiJobId] = useState<string | null>(null)

  const resolvedProviderOptions = useMemo(() => providers.filter((p) => p.enabled), [providers])

  useEffect(() => {
    if (!open) {
      setError(null)
      setAiJobId(null)
      return
    }
    const initialProvider = draft?.provider ?? resolvedProviderOptions[0]?.provider ?? ""
    setProvider((prev) => prev || initialProvider)
    setTitleEn(typeof draft?.titleI18n?.en === "string" ? (draft?.titleI18n?.en as string) : "")
    setTitleZh(typeof draft?.titleI18n?.zh === "string" ? (draft?.titleI18n?.zh as string) : "")
    setDescEn(typeof draft?.descriptionI18n?.en === "string" ? (draft?.descriptionI18n?.en as string) : "")
    setDescZh(typeof draft?.descriptionI18n?.zh === "string" ? (draft?.descriptionI18n?.zh as string) : "")
    setAiJobId((prev) => prev || draft?.lastAiJobId || null)
  }, [open, draft, resolvedProviderOptions])

  const updateDraftMutation = useMutation({
    mutationFn: () =>
      updateTicketDraft({
        provider: provider || undefined,
        titleI18n: buildI18nValue(titleEn, titleZh),
        descriptionI18n: buildI18nValue(descEn, descZh),
      }),
    onSuccess: (next) => {
      queryClient.setQueryData(["ticket-draft", "current"], next)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const clearDraftMutation = useMutation({
    mutationFn: () => clearTicketDraft(),
    onSuccess: (next) => {
      queryClient.setQueryData(["ticket-draft", "current"], next)
      setAiJobId(null)
    },
  })

  const removeItemMutation = useMutation({
    mutationFn: (item: Pick<TicketDraftItem, "type" | "id">) => removeItemsFromTicketDraft({ items: [item] }),
    onSuccess: (next) => {
      queryClient.setQueryData(["ticket-draft", "current"], next)
    },
  })

  const aiGenerateMutation = useMutation<AiJobTicket | undefined>({
    mutationFn: () => generateTicketDraftAi({ provider: provider || undefined }),
    onSuccess: () => {
      void draftQuery.refetch()
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const aiPolishMutation = useMutation<AiJobTicket | undefined>({
    mutationFn: () => polishTicketDraftAi({ provider: provider || undefined }),
    onSuccess: () => {
      void draftQuery.refetch()
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const aiJobQuery = useQuery<AiJobTicket>({
    queryKey: ["ai-job", aiJobId],
    queryFn: () => getAiJob(aiJobId!),
    enabled: open && Boolean(aiJobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status === "QUEUED" || status === "RUNNING" ? 1500 : false
    },
  })
  const aiContent = extractTicketContent(aiJobQuery.data?.result ?? null)

  const applyAiMutation = useMutation({
    mutationFn: () => applyTicketDraftAi({ jobId: aiJobId! }),
    onSuccess: (next) => {
      queryClient.setQueryData(["ticket-draft", "current"], next)
      setTitleEn(typeof next?.titleI18n?.en === "string" ? (next?.titleI18n?.en as string) : "")
      setTitleZh(typeof next?.titleI18n?.zh === "string" ? (next?.titleI18n?.zh as string) : "")
      setDescEn(typeof next?.descriptionI18n?.en === "string" ? (next?.descriptionI18n?.en as string) : "")
      setDescZh(typeof next?.descriptionI18n?.zh === "string" ? (next?.descriptionI18n?.zh as string) : "")
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const createFromDraftMutation = useMutation({
    mutationFn: async () => {
      await updateDraftMutation.mutateAsync()
      return createTicketFromDraft({ provider, issueType, clearDraft: clearDraftAfter })
    },
    onSuccess: () => {
      onCreated()
      if (clearDraftAfter) {
        queryClient.setQueryData<TicketDraftDetail | null>(["ticket-draft", "current"], (prev) => {
          if (!prev) return prev
          return {
            ...prev,
            projectId: null,
            provider: null,
            items: [],
            itemCount: 0,
            titleI18n: null,
            descriptionI18n: null,
            lastAiJobId: null,
            updatedAt: new Date().toISOString(),
          }
        })
        setTitleEn("")
        setTitleZh("")
        setDescEn("")
        setDescZh("")
        setAiJobId(null)
        setProvider(resolvedProviderOptions[0]?.provider ?? "")
        setIssueType("BUG")
        setClearDraftAfter(true)
      }
      queryClient.invalidateQueries({ queryKey: ["ticket-draft", "current"] })
      onOpenChange(false)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  useEffect(() => {
    const jobId = aiGenerateMutation.data?.jobId ?? aiPolishMutation.data?.jobId
    if (jobId) {
      setAiJobId(jobId)
    }
  }, [aiGenerateMutation.data, aiPolishMutation.data])

  const draftEmpty = draftCount === 0

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("tickets.dialog.title")}</DialogTitle>
          <DialogDescription>{t("tickets.dialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-3"
          onSubmit={(event) => {
            event.preventDefault()
            setError(null)
            if (!provider) {
              setError(t("tickets.dialog.errors.required", { defaultValue: "Provider is required." }))
              return
            }
            if (draftEmpty) {
              setError(t("tickets.dialog.errors.emptyDraft", { defaultValue: "Ticket draft is empty." }))
              return
            }
            createFromDraftMutation.mutate()
          }}
        >
          <div className="grid gap-3 md:grid-cols-3">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tickets.dialog.provider")}</label>
              {resolvedProviderOptions.length > 0 ? (
                <select
                  className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                  value={provider}
                  onChange={(event) => setProvider(event.target.value)}
                >
                  <option value="">{t("common.selectPlaceholder")}</option>
                  {resolvedProviderOptions.map((p) => (
                    <option key={p.provider} value={p.provider}>
                      {p.name}
                    </option>
                  ))}
                </select>
              ) : (
                <Input className="mt-1" value={provider} onChange={(event) => setProvider(event.target.value)} />
              )}
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">
                {t("tickets.dialog.issueType", { defaultValue: "Issue type" })}
              </label>
              <select
                className="mt-1 h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
                value={issueType}
                onChange={(event) => setIssueType(event.target.value as TicketIssueType)}
              >
                <option value="BUG">{t("tickets.issueTypes.bug", { defaultValue: "Bug" })}</option>
                <option value="TASK">{t("tickets.issueTypes.task", { defaultValue: "Task" })}</option>
                <option value="STORY">{t("tickets.issueTypes.story", { defaultValue: "Story" })}</option>
              </select>
            </div>
            <div className="flex items-end">
              <label className="flex cursor-pointer items-center gap-2 text-sm text-muted-foreground">
                <Checkbox checked={clearDraftAfter} onChange={(event) => setClearDraftAfter(event.target.checked)} />
                {t("tickets.dialog.clearDraftAfter", { defaultValue: "Clear draft after creation" })}
              </label>
            </div>
          </div>

          <div className="space-y-2 rounded-md border p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="min-w-0 text-sm font-medium">
                {t("tickets.draft.title", { defaultValue: "Draft basket" })} ·{" "}
                <span className="text-muted-foreground">
                  {t("tickets.draft.count", { defaultValue: "{{count}} findings", count: draftCount })}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  disabled={clearDraftMutation.isPending || draftEmpty}
                  onClick={() => clearDraftMutation.mutate()}
                >
                  {t("tickets.draft.clear", { defaultValue: "Clear" })}
                </Button>
                <Button type="button" size="sm" variant="outline" onClick={() => navigate("/findings")}>
                  {t("tickets.draft.goFindings", { defaultValue: "Go to findings" })}
                </Button>
              </div>
            </div>
            {draftQuery.isLoading ? (
              <div className="text-sm text-muted-foreground">
                <Spinner size={18} /> {t("common.loading")}
              </div>
            ) : draftEmpty ? (
              <p className="text-sm text-muted-foreground">{t("tickets.draft.empty", { defaultValue: "Draft is empty." })}</p>
            ) : (
              <div className="max-h-[220px] space-y-2 overflow-auto">
                {draftItems.map((item) => (
                  <div key={`${item.type}:${item.id}`} className="flex min-w-0 items-start justify-between gap-3 rounded-md border p-2">
                    <button
                      type="button"
                      className="min-w-0 flex-1 text-left"
                      onClick={() =>
                        navigate(item.type === "FINDING" ? `/findings/${item.id}` : `/sca/issues/${item.id}`)
                      }
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <SeverityBadge severity={item.severity} />
                        <StatusBadge status={item.status} />
                        <span className="min-w-0 break-all font-mono text-xs text-muted-foreground">
                          {item.title ?? item.id}
                        </span>
                      </div>
                      <div className="mt-1 min-w-0 break-all font-mono text-xs text-muted-foreground">
                        {formatDraftItemLocation(item)}
                      </div>
                    </button>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      disabled={removeItemMutation.isPending}
                      onClick={() => removeItemMutation.mutate({ type: item.type, id: item.id })}
                    >
                      {t("tickets.draft.remove", { defaultValue: "Remove" })}
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="space-y-2 rounded-md border p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-sm font-medium">{t("tickets.ai.title", { defaultValue: "AI copy" })}</div>
              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={aiGenerateMutation.isPending || draftEmpty}
                  onClick={() => {
                    setError(null)
                    aiGenerateMutation.mutate()
                  }}
                >
                  {aiGenerateMutation.isPending ? t("common.loading") : t("tickets.ai.generate", { defaultValue: "AI generate" })}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={aiPolishMutation.isPending || draftEmpty}
                  onClick={() => {
                    setError(null)
                    aiPolishMutation.mutate()
                  }}
                >
                  {aiPolishMutation.isPending ? t("common.loading") : t("tickets.ai.polish", { defaultValue: "AI polish" })}
                </Button>
                {aiJobId ? (
                  <span className="font-mono text-xs text-muted-foreground">
                    {aiJobQuery.data?.status ?? "—"}
                  </span>
                ) : null}
                {aiJobQuery.data?.status === "COMPLETED" ? (
                  <Button
                    type="button"
                    size="sm"
                    disabled={applyAiMutation.isPending}
                    onClick={() => applyAiMutation.mutate()}
                  >
                    {applyAiMutation.isPending ? t("common.loading") : t("tickets.ai.apply", { defaultValue: "Apply" })}
                  </Button>
                ) : null}
              </div>
            </div>
            {aiJobQuery.data?.status === "FAILED" ? (
              <p className="text-sm text-destructive">
                {t("tickets.ai.failed", { defaultValue: "AI failed:" })} {aiJobQuery.data?.error ?? "unknown"}
              </p>
            ) : null}
            {aiContent ? (
              <div className="space-y-2 rounded-md bg-muted/40 p-3 text-sm">
                <div className="text-xs font-medium text-muted-foreground">{t("tickets.ai.preview", { defaultValue: "Preview" })}</div>
                <div className="min-w-0 break-all font-medium">
                  {uiLang === "zh" ? aiContent.titleI18n.zh : aiContent.titleI18n.en}
                </div>
                <div className="max-h-[240px] overflow-y-auto whitespace-pre-wrap text-xs text-muted-foreground">
                  {uiLang === "zh" ? aiContent.descriptionI18n.zh : aiContent.descriptionI18n.en}
                </div>
              </div>
            ) : null}
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tickets.draft.titleEn", { defaultValue: "Title (EN)" })}</label>
              <Input className="mt-1" value={titleEn} onChange={(event) => setTitleEn(event.target.value)} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tickets.draft.titleZh", { defaultValue: "Title (ZH)" })}</label>
              <Input className="mt-1" value={titleZh} onChange={(event) => setTitleZh(event.target.value)} />
            </div>
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tickets.draft.descEn", { defaultValue: "Description (EN)" })}</label>
              <Textarea className="mt-1 min-h-[120px]" value={descEn} onChange={(event) => setDescEn(event.target.value)} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("tickets.draft.descZh", { defaultValue: "Description (ZH)" })}</label>
              <Textarea className="mt-1 min-h-[120px]" value={descZh} onChange={(event) => setDescZh(event.target.value)} />
            </div>
          </div>

          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter className="sm:justify-between">
            <Button
              type="button"
              variant="outline"
              disabled={updateDraftMutation.isPending}
              onClick={() => {
                setError(null)
                updateDraftMutation.mutate()
              }}
            >
              {updateDraftMutation.isPending ? t("common.saving") : t("tickets.dialog.saveDraft", { defaultValue: "Save draft" })}
            </Button>
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:items-center">
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                {t("common.cancel")}
              </Button>
              <Button type="submit" disabled={createFromDraftMutation.isPending}>
                {createFromDraftMutation.isPending ? t("common.loading") : t("tickets.dialog.submit")}
              </Button>
            </div>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function buildI18nValue(en: string, zh: string): Record<string, unknown> {
  const trimmedEn = en.trim()
  const trimmedZh = zh.trim()
  const next: Record<string, unknown> = {}
  if (trimmedEn) next.en = trimmedEn
  if (trimmedZh) next.zh = trimmedZh
  return next
}

function extractTicketContent(result: Record<string, unknown> | null): {
  titleI18n: { zh: string; en: string }
  descriptionI18n: { zh: string; en: string }
} | null {
  if (!result) return null
  const recommendation = result["recommendation"]
  if (!recommendation || typeof recommendation !== "object") return null
  const rec = recommendation as Record<string, unknown>
  const findings = rec["findings"]
  if (!Array.isArray(findings) || findings.length === 0) return null
  const top = findings[0]
  if (!top || typeof top !== "object") return null
  const details = (top as Record<string, unknown>)["details"]
  if (!details || typeof details !== "object") return null
  const content = (details as Record<string, unknown>)["ticketContent"]
  if (!content || typeof content !== "object") return null
  const ticketContent = content as Record<string, unknown>
  const titleI18n = ticketContent["titleI18n"]
  const descriptionI18n = ticketContent["descriptionI18n"]
  if (!titleI18n || typeof titleI18n !== "object") return null
  if (!descriptionI18n || typeof descriptionI18n !== "object") return null
  const title = titleI18n as Record<string, unknown>
  const desc = descriptionI18n as Record<string, unknown>
  const enTitle = typeof title.en === "string" ? title.en : ""
  const zhTitle = typeof title.zh === "string" ? title.zh : ""
  const enDesc = typeof desc.en === "string" ? desc.en : ""
  const zhDesc = typeof desc.zh === "string" ? desc.zh : ""
  if (!enTitle && !zhTitle && !enDesc && !zhDesc) return null
  return {
    titleI18n: { en: enTitle, zh: zhTitle },
    descriptionI18n: { en: enDesc, zh: zhDesc },
  }
}

function formatLocation(location: Record<string, unknown> | null | undefined): string {
  if (!location) return "—"
  const path = typeof location["path"] === "string" ? location["path"] : "—"
  const line =
    typeof location["line"] === "number"
      ? location["line"]
      : typeof location["line"] === "string"
        ? location["line"]
        : typeof location["startLine"] === "number"
          ? location["startLine"]
          : typeof location["startLine"] === "string"
            ? location["startLine"]
            : "—"
  return `${path}:${line}`
}

function formatDraftItemLocation(item: Pick<TicketDraftItem, "type" | "location">): string {
  if (item.type === "FINDING") return formatLocation(item.location)
  const loc = item.location ?? {}
  const pkg = typeof loc["package"] === "string" ? loc["package"] : ""
  const installedVersion = typeof loc["installedVersion"] === "string" ? loc["installedVersion"] : ""
  const purl = typeof loc["purl"] === "string" ? loc["purl"] : ""
  const target = typeof loc["target"] === "string" ? loc["target"] : ""
  const parts = [
    pkg && installedVersion ? `${pkg}@${installedVersion}` : pkg || installedVersion,
    purl,
    target,
  ].filter((part) => typeof part === "string" && part.trim())
  return parts.length > 0 ? parts.join(" · ") : "—"
}
