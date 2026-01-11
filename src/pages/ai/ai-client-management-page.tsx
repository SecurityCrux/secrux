import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { useMutation } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Checkbox } from "@/components/ui/checkbox"
import { Textarea } from "@/components/ui/textarea"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Spinner } from "@/components/ui/spinner"

import { useAiClientsQuery } from "@/hooks/queries/use-ai-clients"
import { useAiAgentsQuery } from "@/hooks/queries/use-ai-agents"
import { useAiMcpsQuery } from "@/hooks/queries/use-ai-mcps"
import { useAiProviderTemplatesQuery } from "@/hooks/queries/use-ai-provider-templates"
import { createAiClient, updateAiClient, deleteAiClient } from "@/services/ai-client-service"
import {
  createAiAgent,
  updateAiAgent,
  deleteAiAgent,
  uploadAiAgent,
  createAiMcp,
  updateAiMcp,
  deleteAiMcp,
  uploadAiMcp,
} from "@/services/ai-integration-service"
import type {
  AiAgentConfig,
  AiAgentConfigPayload,
  AiAgentUploadPayload,
  AiClientConfig,
  AiClientConfigPayload,
  AiMcpConfig,
  AiMcpConfigPayload,
  AiProviderTemplate,
  AiMcpUploadPayload,
} from "@/types/api"

type AiTab = "api" | "agents" | "mcps"

export function AiClientManagementPage() {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<AiTab>("api")
  const apiQuery = useAiClientsQuery()
  const agentQuery = useAiAgentsQuery()
  const mcpQuery = useAiMcpsQuery()

  const [apiDialog, setApiDialog] = useState<{ open: boolean; editing?: AiClientConfig | null }>({ open: false })
  const [agentDialog, setAgentDialog] = useState<{ open: boolean; editing?: AiAgentConfig | null }>({ open: false })
  const [mcpDialog, setMcpDialog] = useState<{ open: boolean; editing?: AiMcpConfig | null }>({ open: false })
  const [mcpUploadDialog, setMcpUploadDialog] = useState<{ open: boolean }>({ open: false })
  const [agentUploadDialog, setAgentUploadDialog] = useState<{ open: boolean }>({ open: false })

  const tabs = useMemo(
    () => [
      { key: "api" as AiTab, label: t("aiClients.tabs.api") },
      { key: "agents" as AiTab, label: t("aiClients.tabs.agents") },
      { key: "mcps" as AiTab, label: t("aiClients.tabs.mcps") },
    ],
    [t]
  )

  const createButtonLabel = useMemo(() => {
    if (activeTab === "api") return t("aiClients.actions.create")
    if (activeTab === "agents") return t("aiAgents.actions.create")
    return t("aiMcps.actions.create")
  }, [activeTab, t])

  const handleCreateClick = () => {
    if (activeTab === "api") {
      setApiDialog({ open: true, editing: null })
    } else if (activeTab === "agents") {
      setAgentDialog({ open: true, editing: null })
    } else {
      setMcpDialog({ open: true, editing: null })
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("aiClients.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("aiClients.subtitle")}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          {activeTab === "agents" ? (
            <Button variant="outline" onClick={() => setAgentUploadDialog({ open: true })}>
              {t("aiAgents.actions.upload")}
            </Button>
          ) : null}
          {activeTab === "mcps" ? (
            <Button variant="outline" onClick={() => setMcpUploadDialog({ open: true })}>
              {t("aiMcps.actions.upload")}
            </Button>
          ) : null}
          <Button onClick={handleCreateClick}>{createButtonLabel}</Button>
        </div>
      </div>

      <div className="flex flex-wrap gap-2">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            type="button"
            onClick={() => setActiveTab(tab.key)}
            className={`rounded-md border px-3 py-1 text-sm font-medium transition ${
              activeTab === tab.key ? "border-primary bg-primary/10 text-primary" : "border-border text-muted-foreground"
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === "api" ? (
        <ApiConfigTable query={apiQuery} onEdit={(config) => setApiDialog({ open: true, editing: config })} />
      ) : activeTab === "agents" ? (
        <AgentConfigTable
          mcps={mcpQuery.data ?? []}
          query={agentQuery}
          onEdit={(agent) => setAgentDialog({ open: true, editing: agent })}
        />
      ) : (
        <McpConfigTable query={mcpQuery} onEdit={(profile) => setMcpDialog({ open: true, editing: profile })} />
      )}

      <AiClientDialog
        open={apiDialog.open}
        editing={apiDialog.editing ?? undefined}
        onOpenChange={(open) => setApiDialog({ open })}
        onSaved={() => void apiQuery.refetch()}
      />
      <AiAgentDialog
        open={agentDialog.open}
        editing={agentDialog.editing ?? undefined}
        mcps={mcpQuery.data ?? []}
        onOpenChange={(open) => setAgentDialog({ open })}
        onSaved={() => void agentQuery.refetch()}
      />
      <AiMcpDialog
        open={mcpDialog.open}
        editing={mcpDialog.editing ?? undefined}
        onOpenChange={(open) => setMcpDialog({ open })}
        onSaved={() => void mcpQuery.refetch()}
      />
      <AiMcpUploadDialog
        open={mcpUploadDialog.open}
        onOpenChange={(open) => setMcpUploadDialog({ open })}
        onSaved={() => void mcpQuery.refetch()}
      />
      <AiAgentUploadDialog
        open={agentUploadDialog.open}
        mcps={mcpQuery.data ?? []}
        onOpenChange={(open) => setAgentUploadDialog({ open })}
        onSaved={() => void agentQuery.refetch()}
      />
    </div>
  )
}

type ApiTableProps = {
  query: ReturnType<typeof useAiClientsQuery>
  onEdit: (config: AiClientConfig) => void
}

function ApiConfigTable({ query, onEdit }: ApiTableProps) {
  const { t } = useTranslation()
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("aiClients.listTitle")}</CardTitle>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <div className="py-6 text-sm text-muted-foreground">
            <Spinner size={18} /> {t("common.loading")}
          </div>
        ) : query.isError ? (
          <p className="py-6 text-sm text-destructive">{t("aiClients.error")}</p>
        ) : query.data && query.data.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="border-b text-muted-foreground">
                <tr>
                  <th className="py-2 text-left">{t("aiClients.columns.name")}</th>
                  <th className="py-2 text-left">{t("aiClients.columns.provider")}</th>
                  <th className="py-2 text-left">{t("aiClients.columns.model")}</th>
                  <th className="py-2 text-left">{t("aiClients.columns.baseUrl")}</th>
                  <th className="py-2 text-left">{t("aiClients.columns.default")}</th>
                  <th className="py-2 text-left">{t("aiClients.columns.enabled")}</th>
                  <th className="py-2 text-left">{t("aiClients.columns.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {query.data.map((config) => (
                  <tr key={config.configId} className="border-b last:border-b-0">
                    <td className="py-2 font-medium">{config.name}</td>
                    <td className="py-2">{config.provider}</td>
                    <td className="py-2">{config.model}</td>
                    <td className="py-2 break-all text-xs text-muted-foreground">{config.baseUrl}</td>
                    <td className="py-2">{config.isDefault ? t("common.yes") : t("common.no")}</td>
                    <td className="py-2">{config.enabled ? t("common.yes") : t("common.no")}</td>
                    <td className="py-2 space-x-2">
                      <Button variant="ghost" size="sm" onClick={() => onEdit(config)}>
                        {t("aiClients.actions.edit")}
                      </Button>
                      <DeleteAiClientButton configId={config.configId} onDeleted={() => void query.refetch()} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-6 text-sm text-muted-foreground">{t("aiClients.empty")}</p>
        )}
      </CardContent>
    </Card>
  )
}


type AgentTableProps = {
  query: ReturnType<typeof useAiAgentsQuery>
  mcps: AiMcpConfig[]
  onEdit: (agent: AiAgentConfig) => void
}

function AgentConfigTable({ query, mcps, onEdit }: AgentTableProps) {
  const { t } = useTranslation()
  const mcpLookup = useMemo(() => Object.fromEntries(mcps.map((mcp) => [mcp.profileId, mcp.name])), [mcps])
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("aiAgents.listTitle")}</CardTitle>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <div className="py-6 text-sm text-muted-foreground">
            <Spinner size={18} /> {t("common.loading")}
          </div>
        ) : query.isError ? (
          <p className="py-6 text-sm text-destructive">{t("aiAgents.error")}</p>
        ) : query.data && query.data.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="border-b text-muted-foreground">
                <tr>
                  <th className="py-2 text-left">{t("aiAgents.columns.name")}</th>
                  <th className="py-2 text-left">{t("aiAgents.columns.kind")}</th>
                  <th className="py-2 text-left">{t("aiAgents.columns.stageTypes")}</th>
                  <th className="py-2 text-left">{t("aiAgents.columns.mcp")}</th>
                  <th className="py-2 text-left">{t("aiAgents.columns.enabled")}</th>
                  <th className="py-2 text-left">{t("aiAgents.columns.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {query.data.map((agent) => (
                  <tr key={agent.agentId} className="border-b last:border-b-0">
                    <td className="py-2 font-medium">{agent.name}</td>
                    <td className="py-2">{agent.kind}</td>
                    <td className="py-2">{agent.stageTypes.length > 0 ? agent.stageTypes.join(", ") : t("common.all")}</td>
                    <td className="py-2">{agent.mcpProfileId ? mcpLookup[agent.mcpProfileId] ?? "—" : "—"}</td>
                    <td className="py-2">{agent.enabled ? t("common.yes") : t("common.no")}</td>
                    <td className="py-2 space-x-2">
                      <Button variant="ghost" size="sm" onClick={() => onEdit(agent)}>
                        {t("common.edit")}
                      </Button>
                      <DeleteAiAgentButton agentId={agent.agentId} onDeleted={() => void query.refetch()} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-6 text-sm text-muted-foreground">{t("aiAgents.empty")}</p>
        )}
      </CardContent>
    </Card>
  )
}

type McpTableProps = {
  query: ReturnType<typeof useAiMcpsQuery>
  onEdit: (profile: AiMcpConfig) => void
}

function McpConfigTable({ query, onEdit }: McpTableProps) {
  const { t } = useTranslation()
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("aiMcps.listTitle")}</CardTitle>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <div className="py-6 text-sm text-muted-foreground">
            <Spinner size={18} /> {t("common.loading")}
          </div>
        ) : query.isError ? (
          <p className="py-6 text-sm text-destructive">{t("aiMcps.error")}</p>
        ) : query.data && query.data.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="border-b text-muted-foreground">
                <tr>
                  <th className="py-2 text-left">{t("aiMcps.columns.name")}</th>
                  <th className="py-2 text-left">{t("aiMcps.columns.type")}</th>
                  <th className="py-2 text-left">{t("aiMcps.columns.endpoint")}</th>
                  <th className="py-2 text-left">{t("aiMcps.columns.entrypoint")}</th>
                  <th className="py-2 text-left">{t("aiMcps.columns.enabled")}</th>
                  <th className="py-2 text-left">{t("aiMcps.columns.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {query.data.map((profile) => (
                  <tr key={profile.profileId} className="border-b last:border-b-0">
                    <td className="py-2 font-medium">{profile.name}</td>
                    <td className="py-2">{profile.type}</td>
                    <td className="py-2 break-all text-xs text-muted-foreground">{profile.endpoint ?? "—"}</td>
                    <td className="py-2 break-all text-xs text-muted-foreground">{profile.entrypoint ?? "—"}</td>
                    <td className="py-2">{profile.enabled ? t("common.yes") : t("common.no")}</td>
                    <td className="py-2 space-x-2">
                      <Button variant="ghost" size="sm" onClick={() => onEdit(profile)}>
                        {t("common.edit")}
                      </Button>
                      <DeleteAiMcpButton profileId={profile.profileId} onDeleted={() => void query.refetch()} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-6 text-sm text-muted-foreground">{t("aiMcps.empty")}</p>
        )}
      </CardContent>
    </Card>
  )
}

type AiClientDialogProps = {
  open: boolean
  editing?: AiClientConfig
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}

function AiClientDialog({ open, editing, onOpenChange, onSaved }: AiClientDialogProps) {
  const { t } = useTranslation()
  const templatesQuery = useAiProviderTemplatesQuery()
  const [form, setForm] = useState<AiClientConfigPayload>({
    name: "",
    provider: "",
    baseUrl: "",
    apiKey: "",
    model: "",
    isDefault: false,
    enabled: true,
  })
  const [presetProvider, setPresetProvider] = useState("")
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (editing) {
      setForm({
        name: editing.name,
        provider: editing.provider,
        baseUrl: editing.baseUrl,
        apiKey: "",
        model: editing.model,
        isDefault: editing.isDefault,
        enabled: editing.enabled,
      })
    } else {
      setForm({
        name: "",
        provider: "",
        baseUrl: "",
        apiKey: "",
        model: "",
        isDefault: false,
        enabled: true,
      })
    }
    setPresetProvider("")
    setError(null)
  }, [editing, open])

  const templateMap = useMemo(() => {
    const data = templatesQuery.data ?? []
    return Object.fromEntries(data.map((template) => [template.provider, template]))
  }, [templatesQuery.data])

  const selectedTemplate = presetProvider ? templateMap[presetProvider] : undefined
  const providerTemplate = templateMap[form.provider] ?? selectedTemplate
  const availableModels = providerTemplate?.models ?? []

  const regionGroups = useMemo(() => {
    const groups: Record<string, AiProviderTemplate[]> = {}
    ;(templatesQuery.data ?? []).forEach((template) => {
      const key = template.regions[0] ?? "other"
      groups[key] = [...(groups[key] ?? []), template]
    })
    return groups
  }, [templatesQuery.data])

  const regionKeys = useMemo(() => {
    const keys = Object.keys(regionGroups)
    const preferred = ["global", "china"]
    const orderedPreferred = preferred.filter((key) => keys.includes(key))
    const rest = keys.filter((key) => !preferred.includes(key)).sort()
    return [...orderedPreferred, ...rest]
  }, [regionGroups])

  const regionLabel = (region: string) => {
    if (region === "global") {
      return t("aiClients.dialog.presets.regions.global")
    }
    if (region === "china") {
      return t("aiClients.dialog.presets.regions.china")
    }
    return region
  }

  const mutation = useMutation({
    mutationFn: (payload: AiClientConfigPayload) =>
      editing ? updateAiClient(editing.configId, payload) : createAiClient(payload),
    onSuccess: () => {
      onSaved()
      onOpenChange(false)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    if (!form.name || !form.provider || !form.baseUrl || !form.model) {
      setError(t("aiClients.dialog.errors.required"))
      return
    }
    mutation.mutate(form)
  }

  const handleApplyPreset = () => {
    if (!selectedTemplate) return
    setForm((prev) => ({
      ...prev,
      name: prev.name || selectedTemplate.name,
      provider: selectedTemplate.provider,
      baseUrl: selectedTemplate.baseUrl,
      model: selectedTemplate.defaultModel,
    }))
    setError(null)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{editing ? t("aiClients.dialog.editTitle") : t("aiClients.dialog.title")}</DialogTitle>
          <DialogDescription>{t("aiClients.dialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="space-y-3" onSubmit={handleSubmit}>
          <div className="space-y-2 rounded-md border p-3">
            <div className="flex items-center justify-between gap-2">
              <div>
                <p className="text-sm font-medium">{t("aiClients.dialog.presets.title")}</p>
                <p className="text-xs text-muted-foreground">{t("aiClients.dialog.presets.subtitle")}</p>
              </div>
              {templatesQuery.isLoading ? <Spinner size={16} /> : null}
            </div>
            {templatesQuery.isError ? (
              <p className="text-xs text-destructive">{t("aiClients.dialog.presets.error")}</p>
            ) : templatesQuery.data && templatesQuery.data.length > 0 ? (
              <>
                <div className="flex flex-col gap-2 md:flex-row md:items-center">
                  <select
                    className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                    value={presetProvider}
                    disabled={templatesQuery.isLoading}
                    onChange={(event) => setPresetProvider(event.target.value)}
                  >
                    <option value="">{t("aiClients.dialog.presets.placeholder")}</option>
                    {regionKeys.map((region) => (
                      <optgroup key={region} label={regionLabel(region)}>
                        {regionGroups[region]?.map((template) => (
                          <option key={template.provider} value={template.provider}>
                            {template.name}
                          </option>
                        ))}
                      </optgroup>
                    ))}
                  </select>
                  <Button type="button" variant="outline" disabled={!selectedTemplate} onClick={handleApplyPreset}>
                    {t("aiClients.dialog.presets.apply")}
                  </Button>
                </div>
                {selectedTemplate ? (
                  <p className="text-xs text-muted-foreground">
                    {selectedTemplate.description ?? selectedTemplate.baseUrl}
                    {selectedTemplate.docsUrl ? (
                      <>
                        {" "}
                        <a className="text-primary underline" href={selectedTemplate.docsUrl} target="_blank" rel="noreferrer">
                          {t("aiClients.dialog.presets.docs")}
                        </a>
                      </>
                    ) : null}
                  </p>
                ) : null}
              </>
            ) : (
              <p className="text-xs text-muted-foreground">{t("aiClients.dialog.presets.empty")}</p>
            )}
          </div>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiClients.columns.name")}</label>
              <Input className="mt-1" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiClients.columns.provider")}</label>
              <Input className="mt-1" value={form.provider} onChange={(event) => setForm({ ...form, provider: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiClients.columns.baseUrl")}</label>
              <Input className="mt-1" value={form.baseUrl} onChange={(event) => setForm({ ...form, baseUrl: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiClients.dialog.apiKey")}</label>
              <Input
                className="mt-1"
                type="password"
                value={form.apiKey ?? ""}
                onChange={(event) => setForm({ ...form, apiKey: event.target.value })}
              />
            </div>
            {availableModels.length > 0 ? (
              <div className="md:col-span-2">
                <label className="text-xs font-medium text-muted-foreground">
                  {t("aiClients.dialog.modelSelectLabel", { count: availableModels.length })}
                </label>
                <select
                  className="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                  value={availableModels.includes(form.model) ? form.model : ""}
                  onChange={(event) => {
                    const value = event.target.value
                    if (!value) return
                    setForm((prev) => ({ ...prev, model: value }))
                  }}
                >
                  <option value="">{t("aiClients.dialog.modelSelectPlaceholder")}</option>
                  {availableModels.map((model) => (
                    <option key={model} value={model}>
                      {model}
                    </option>
                  ))}
                </select>
                <p className="mt-1 text-xs text-muted-foreground">{t("aiClients.dialog.modelSelectHint")}</p>
              </div>
            ) : null}
            <div className="md:col-span-2">
              <label className="text-xs font-medium text-muted-foreground">{t("aiClients.dialog.model")}</label>
              <Input
                className="mt-1"
                value={form.model}
                onChange={(event) => setForm({ ...form, model: event.target.value })}
                placeholder={t("aiClients.dialog.customModelPlaceholder") ?? undefined}
              />
              <p className="mt-1 text-xs text-muted-foreground">{t("aiClients.dialog.customModelHint")}</p>
            </div>
          </div>
          <div className="flex items-center justify-between rounded-md border p-3">
            <div>
              <p className="text-sm font-medium">{t("aiClients.dialog.defaultLabel")}</p>
              <p className="text-xs text-muted-foreground">{t("aiClients.dialog.defaultHint")}</p>
            </div>
            <Checkbox checked={form.isDefault} onChange={(event) => setForm({ ...form, isDefault: event.target.checked })} />
          </div>
          <div className="flex items-center justify-between rounded-md border p-3">
            <div>
              <p className="text-sm font-medium">{t("aiClients.dialog.enabledLabel")}</p>
              <p className="text-xs text-muted-foreground">{t("aiClients.dialog.enabledHint")}</p>
            </div>
            <Checkbox checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t("common.loading") : t("aiClients.dialog.submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type AiAgentDialogProps = {
  open: boolean
  editing?: AiAgentConfig
  mcps: AiMcpConfig[]
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}

function AiAgentDialog({ open, editing, mcps, onOpenChange, onSaved }: AiAgentDialogProps) {
  const { t } = useTranslation()
  const [form, setForm] = useState({
    name: "",
    kind: "",
    entrypoint: "",
    paramsText: "{}",
    stageTypesText: "",
    mcpProfileId: "",
    enabled: true,
  })
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (editing) {
      setForm({
        name: editing.name,
        kind: editing.kind,
        entrypoint: editing.entrypoint ?? "",
        paramsText: JSON.stringify(editing.params ?? {}, null, 2),
        stageTypesText: editing.stageTypes.join(", "),
        mcpProfileId: editing.mcpProfileId ?? "",
        enabled: editing.enabled,
      })
    } else {
      setForm({
        name: "",
        kind: "",
        entrypoint: "",
        paramsText: "{}",
        stageTypesText: "",
        mcpProfileId: "",
        enabled: true,
      })
    }
    setError(null)
  }, [editing, open])

  const mutation = useMutation({
    mutationFn: (payload: AiAgentConfigPayload) =>
      editing ? updateAiAgent(editing.agentId, payload) : createAiAgent(payload),
    onSuccess: () => {
      onSaved()
      onOpenChange(false)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    if (!form.name || !form.kind) {
      setError(t("aiAgents.dialog.errors.required"))
      return
    }
    let params: Record<string, unknown> = {}
    if (form.paramsText.trim().length > 0) {
      try {
        params = JSON.parse(form.paramsText)
      } catch {
        setError(t("aiAgents.dialog.errors.params"))
        return
      }
    }
    const stageTypes = form.stageTypesText
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean)
    const payload: AiAgentConfigPayload = {
      name: form.name,
      kind: form.kind,
      entrypoint: form.entrypoint || undefined,
      params,
      stageTypes,
      mcpProfileId: form.mcpProfileId || undefined,
      enabled: form.enabled,
    }
    mutation.mutate(payload)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{editing ? t("aiAgents.dialog.editTitle") : t("aiAgents.dialog.title")}</DialogTitle>
          <DialogDescription>{t("aiAgents.dialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="space-y-3" onSubmit={handleSubmit}>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.columns.name")}</label>
              <Input className="mt-1" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.columns.kind")}</label>
              <Input className="mt-1" value={form.kind} onChange={(event) => setForm({ ...form, kind: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.dialog.entrypoint")}</label>
              <Input
                className="mt-1"
                value={form.entrypoint}
                placeholder="module:ClassName"
                onChange={(event) => setForm({ ...form, entrypoint: event.target.value })}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.dialog.stageTypes")}</label>
              <Input
                className="mt-1"
                value={form.stageTypesText}
                placeholder="SCAN_EXEC, RESULT_PROCESS"
                onChange={(event) => setForm({ ...form, stageTypesText: event.target.value })}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.columns.mcp")}</label>
              <select
                className="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                value={form.mcpProfileId}
                onChange={(event) => setForm({ ...form, mcpProfileId: event.target.value })}
              >
                <option value="">{t("common.selectPlaceholder")}</option>
                {mcps.map((mcp) => (
                  <option key={mcp.profileId} value={mcp.profileId}>
                    {mcp.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex items-center gap-2">
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.columns.enabled")}</label>
              <Checkbox checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />
            </div>
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.dialog.params")}</label>
            <Textarea
              className="mt-1 font-mono text-xs"
              rows={6}
              value={form.paramsText}
              onChange={(event) => setForm({ ...form, paramsText: event.target.value })}
            />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t("common.loading") : t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type AiMcpDialogProps = {
  open: boolean
  editing?: AiMcpConfig
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}

function AiMcpDialog({ open, editing, onOpenChange, onSaved }: AiMcpDialogProps) {
  const { t } = useTranslation()
  const [form, setForm] = useState({
    name: "",
    type: "",
    endpoint: "",
    entrypoint: "",
    paramsText: "{}",
    enabled: true,
  })
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (editing) {
      setForm({
        name: editing.name,
        type: editing.type,
        endpoint: editing.endpoint ?? "",
        entrypoint: editing.entrypoint ?? "",
        paramsText: JSON.stringify(editing.params ?? {}, null, 2),
        enabled: editing.enabled,
      })
    } else {
      setForm({
        name: "",
        type: "",
        endpoint: "",
        entrypoint: "",
        paramsText: "{}",
        enabled: true,
      })
    }
    setError(null)
  }, [editing, open])

  const mutation = useMutation({
    mutationFn: (payload: AiMcpConfigPayload) =>
      editing ? updateAiMcp(editing.profileId, payload) : createAiMcp(payload),
    onSuccess: () => {
      onSaved()
      onOpenChange(false)
    },
    onError: (err: unknown) => setError(err instanceof Error ? err.message : "unknown"),
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    if (!form.name || !form.type) {
      setError(t("aiMcps.dialog.errors.required"))
      return
    }
    let params: Record<string, unknown> = {}
    if (form.paramsText.trim().length > 0) {
      try {
        params = JSON.parse(form.paramsText)
      } catch {
        setError(t("aiMcps.dialog.errors.params"))
        return
      }
    }
    const payload: AiMcpConfigPayload = {
      name: form.name,
      type: form.type,
      endpoint: form.endpoint || undefined,
      entrypoint: form.entrypoint || undefined,
      params,
      enabled: form.enabled,
    }
    mutation.mutate(payload)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{editing ? t("aiMcps.dialog.editTitle") : t("aiMcps.dialog.title")}</DialogTitle>
          <DialogDescription>{t("aiMcps.dialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="space-y-3" onSubmit={handleSubmit}>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.columns.name")}</label>
              <Input className="mt-1" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.columns.type")}</label>
              <Input className="mt-1" value={form.type} onChange={(event) => setForm({ ...form, type: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.columns.endpoint")}</label>
              <Input className="mt-1" value={form.endpoint} onChange={(event) => setForm({ ...form, endpoint: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.columns.entrypoint")}</label>
              <Input
                className="mt-1"
                placeholder="module:ClientClass"
                value={form.entrypoint}
                onChange={(event) => setForm({ ...form, entrypoint: event.target.value })}
              />
            </div>
            <div className="flex items-center gap-2">
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.columns.enabled")}</label>
              <Checkbox checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />
            </div>
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.dialog.params")}</label>
            <Textarea
              className="mt-1 font-mono text-xs"
              rows={6}
              value={form.paramsText}
              onChange={(event) => setForm({ ...form, paramsText: event.target.value })}
            />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t("common.loading") : t("common.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type AiAgentUploadDialogProps = {
  open: boolean
  mcps: AiMcpConfig[]
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}

function AiAgentUploadDialog({ open, mcps, onOpenChange, onSaved }: AiAgentUploadDialogProps) {
  const { t } = useTranslation()
  const [form, setForm] = useState({
    name: "",
    kind: "",
    entrypoint: "",
    stageTypesText: "",
    mcpProfileId: "",
    paramsText: "{}",
    enabled: true,
  })
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    setForm({
      name: "",
      kind: "",
      entrypoint: "",
      stageTypesText: "",
      mcpProfileId: "",
      paramsText: "{}",
      enabled: true,
    })
    setFile(null)
    setError(null)
  }, [open])

  const mutation = useMutation({
    mutationFn: (payload: AiAgentUploadPayload) => uploadAiAgent(payload),
    onSuccess: () => {
      onSaved()
      onOpenChange(false)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    if (!form.name || !form.kind) {
      setError(t("aiAgents.dialog.errors.required"))
      return
    }
    if (!file) {
      setError(t("aiAgents.uploadDialog.errors.file"))
      return
    }
    let params: Record<string, unknown> = {}
    if (form.paramsText.trim().length > 0) {
      try {
        params = JSON.parse(form.paramsText)
      } catch {
        setError(t("aiAgents.uploadDialog.errors.params"))
        return
      }
    }
    const stageTypes = form.stageTypesText
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean)
    const payload: AiAgentUploadPayload = {
      name: form.name,
      kind: form.kind,
      entrypoint: form.entrypoint || undefined,
      stageTypes,
      mcpProfileId: form.mcpProfileId || undefined,
      enabled: form.enabled,
      params,
      file,
    }
    mutation.mutate(payload)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{t("aiAgents.uploadDialog.title")}</DialogTitle>
          <DialogDescription>{t("aiAgents.uploadDialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="space-y-3" onSubmit={handleSubmit}>
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.columns.name")}</label>
              <Input className="mt-1" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.columns.kind")}</label>
              <Input className="mt-1" value={form.kind} onChange={(event) => setForm({ ...form, kind: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.dialog.entrypoint")}</label>
              <Input
                className="mt-1"
                value={form.entrypoint}
                placeholder="module:ClassName"
                onChange={(event) => setForm({ ...form, entrypoint: event.target.value })}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.dialog.stageTypes")}</label>
              <Input
                className="mt-1"
                value={form.stageTypesText}
                placeholder="SCAN_EXEC, RESULT_PROCESS"
                onChange={(event) => setForm({ ...form, stageTypesText: event.target.value })}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.columns.mcp")}</label>
              <select
                className="mt-1 h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                value={form.mcpProfileId}
                onChange={(event) => setForm({ ...form, mcpProfileId: event.target.value })}
              >
                <option value="">{t("common.selectPlaceholder")}</option>
                {mcps.map((mcp) => (
                  <option key={mcp.profileId} value={mcp.profileId}>
                    {mcp.name}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.uploadDialog.file")}</label>
              <Input
                className="mt-1"
                type="file"
                accept=".zip,.tar,.tar.gz,.tgz"
                onChange={(event) => {
                  setFile(event.target.files?.[0] ?? null)
                }}
              />
            </div>
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("aiAgents.dialog.params")}</label>
            <Textarea
              className="mt-1 font-mono text-xs"
              rows={5}
              value={form.paramsText}
              placeholder={t("aiAgents.uploadDialog.paramsHint") ?? undefined}
              onChange={(event) => setForm({ ...form, paramsText: event.target.value })}
            />
          </div>
          <div className="flex items-center justify-between rounded-md border p-3">
            <div>
              <p className="text-sm font-medium">{t("aiAgents.columns.enabled")}</p>
              <p className="text-xs text-muted-foreground">{t("aiAgents.uploadDialog.enabledHint")}</p>
            </div>
            <Checkbox checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t("common.loading") : t("aiAgents.uploadDialog.submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type AiMcpUploadDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}

function AiMcpUploadDialog({ open, onOpenChange, onSaved }: AiMcpUploadDialogProps) {
  const { t } = useTranslation()
  const [form, setForm] = useState({
    name: "",
    entrypoint: "",
    paramsText: "{}",
    enabled: true,
  })
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setForm({
      name: "",
      entrypoint: "",
      paramsText: "{}",
      enabled: true,
    })
    setFile(null)
    setError(null)
  }, [open])

  const mutation = useMutation({
    mutationFn: (payload: AiMcpUploadPayload) => uploadAiMcp(payload),
    onSuccess: () => {
      onSaved()
      onOpenChange(false)
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : "unknown")
    },
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    if (!form.name) {
      setError(t("aiMcps.dialog.errors.required"))
      return
    }
    if (!file) {
      setError(t("aiMcps.uploadDialog.errors.file"))
      return
    }
    let params: Record<string, unknown> = {}
    if (form.paramsText.trim().length > 0) {
      try {
        params = JSON.parse(form.paramsText)
      } catch {
        setError(t("aiMcps.uploadDialog.errors.params"))
        return
      }
    }
    mutation.mutate({
      name: form.name,
      entrypoint: form.entrypoint || undefined,
      enabled: form.enabled,
      params,
      file,
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("aiMcps.uploadDialog.title")}</DialogTitle>
          <DialogDescription>{t("aiMcps.uploadDialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="space-y-3" onSubmit={handleSubmit}>
          <div className="grid gap-3">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.columns.name")}</label>
              <Input className="mt-1" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.dialog.entrypoint")}</label>
              <Input className="mt-1" value={form.entrypoint} onChange={(event) => setForm({ ...form, entrypoint: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.uploadDialog.file")}</label>
              <Input
                className="mt-1"
                type="file"
                accept=".zip,.tar,.tar.gz,.tgz"
                onChange={(event) => {
                  setFile(event.target.files?.[0] ?? null)
                }}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiMcps.dialog.params")}</label>
              <Textarea
                className="mt-1 font-mono text-xs"
                rows={4}
                value={form.paramsText}
                placeholder={t("aiMcps.uploadDialog.paramsHint") ?? undefined}
                onChange={(event) => setForm({ ...form, paramsText: event.target.value })}
              />
            </div>
          </div>
          <div className="flex items-center justify-between rounded-md border p-3">
            <div>
              <p className="text-sm font-medium">{t("aiMcps.dialog.enabledLabel")}</p>
              <p className="text-xs text-muted-foreground">{t("aiMcps.dialog.enabledHint")}</p>
            </div>
            <Checkbox checked={form.enabled} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t("common.loading") : t("aiMcps.uploadDialog.submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function DeleteAiClientButton({ configId, onDeleted }: { configId: string; onDeleted: () => void }) {
  const { t } = useTranslation()
  const mutation = useMutation({
    mutationFn: () => deleteAiClient(configId),
    onSuccess: () => onDeleted(),
  })

  return (
    <Button
      variant="ghost"
      size="sm"
      className="text-destructive"
      disabled={mutation.isPending}
      onClick={() => mutation.mutate()}
    >
      {t("aiClients.actions.delete")}
    </Button>
  )
}

function DeleteAiAgentButton({ agentId, onDeleted }: { agentId: string; onDeleted: () => void }) {
  const { t } = useTranslation()
  const mutation = useMutation({
    mutationFn: () => deleteAiAgent(agentId),
    onSuccess: () => onDeleted(),
  })
  return (
    <Button
      variant="ghost"
      size="sm"
      className="text-destructive"
      disabled={mutation.isPending}
      onClick={() => mutation.mutate()}
    >
      {t("common.delete")}
    </Button>
  )
}

function DeleteAiMcpButton({ profileId, onDeleted }: { profileId: string; onDeleted: () => void }) {
  const { t } = useTranslation()
  const mutation = useMutation({
    mutationFn: () => deleteAiMcp(profileId),
    onSuccess: () => onDeleted(),
  })
  return (
    <Button
      variant="ghost"
      size="sm"
      className="text-destructive"
      disabled={mutation.isPending}
      onClick={() => mutation.mutate()}
    >
      {t("common.delete")}
    </Button>
  )
}
