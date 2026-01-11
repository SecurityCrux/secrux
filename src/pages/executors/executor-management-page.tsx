import { useEffect, useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { useMutation } from "@tanstack/react-query"
import { useForm } from "react-hook-form"
import { zodResolver } from "@hookform/resolvers/zod"
import * as z from "zod"

import { useExecutorsQuery } from "@/hooks/queries/use-executors"
import { getExecutorToken, registerExecutor, updateExecutorStatus } from "@/services/executor-service"
import type { ExecutorRegisterPayload, ExecutorStatus, ExecutorSummary } from "@/types/api"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Spinner } from "@/components/ui/spinner"
import { Badge } from "@/components/ui/badge"
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { formatGbFromMb, formatPercent } from "@/lib/utils"

const registerSchema = z.object({
  name: z.string().min(2),
  cpuCapacity: z.number().min(1),
  memoryCapacityMb: z.number().min(128),
  labels: z.string().optional(),
})

export function ExecutorManagementPage() {
  const { t } = useTranslation()
  const [statusFilter, setStatusFilter] = useState<ExecutorStatus | "ALL">("ALL")
  const [search, setSearch] = useState("")
  const executorsQuery = useExecutorsQuery({
    status: statusFilter === "ALL" ? undefined : statusFilter,
    search: search || undefined,
  })

  const [registerOpen, setRegisterOpen] = useState(false)
  const [tokenDialog, setTokenDialog] = useState({
    open: false,
    executorName: "",
    token: "",
    error: null as string | null,
  })
  const registerForm = useForm({
    resolver: zodResolver(registerSchema),
    defaultValues: {
      name: "",
      cpuCapacity: 4,
      memoryCapacityMb: 4096,
      labels: "",
    },
  })

  const registerMutation = useMutation({
    mutationFn: async (payload: ExecutorRegisterPayload) => registerExecutor(payload),
    onSuccess: () => {
      setRegisterOpen(false)
      void executorsQuery.refetch()
      registerForm.reset()
    },
  })

  const tokenMutation = useMutation({
    mutationFn: (executorId: string) => getExecutorToken(executorId),
    onSuccess: (data) => {
      setTokenDialog((prev) => ({
        ...prev,
        token: data?.token ?? "",
        error: null,
      }))
    },
    onError: (err) => {
      setTokenDialog((prev) => ({
        ...prev,
        error: err instanceof Error ? err.message : "unknown",
      }))
    },
  })

  const statusOptions: ExecutorStatus[] = ["READY", "BUSY", "DRAINING", "OFFLINE", "REGISTERED"]

  const filteredExecutors = useMemo(() => executorsQuery.data ?? [], [executorsQuery.data])

  const handleViewToken = (executor: ExecutorSummary) => {
    setTokenDialog({
      open: true,
      executorName: executor.name,
      token: "",
      error: null,
    })
    tokenMutation.mutate(executor.executorId)
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("executors.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("executors.subtitle")}</p>
        </div>
        <Button onClick={() => setRegisterOpen(true)}>{t("executors.actions.register")}</Button>
      </div>
      <Card>
        <CardHeader className="space-y-3">
          <CardTitle className="text-base">{t("executors.filters.title")}</CardTitle>
          <div className="flex flex-col gap-3 md:flex-row">
            <div className="flex flex-1 flex-col gap-1">
              <Label>{t("executors.filters.search")}</Label>
              <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="runner-1" />
            </div>
            <div className="flex flex-1 flex-col gap-1">
              <Label>{t("executors.filters.status")}</Label>
              <Select
                value={statusFilter}
                onValueChange={(value: ExecutorStatus | "ALL") => setStatusFilter(value)}
              >
                <SelectTrigger>
                  <SelectValue placeholder={t("executors.filters.status")} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">{t("common.all")}</SelectItem>
                  {statusOptions.map((status) => (
                    <SelectItem key={status} value={status}>
                      {t(`executors.status.${status.toLowerCase()}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {executorsQuery.isLoading ? (
            <div className="flex items-center gap-2 text-muted-foreground">
              <Spinner size={16} />
              {t("common.loading")}
            </div>
          ) : filteredExecutors.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("executors.empty")}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b text-xs uppercase text-muted-foreground">
                    <th className="py-2">{t("executors.table.name")}</th>
                    <th>{t("executors.table.status")}</th>
                    <th>{t("executors.table.cpu")}</th>
                    <th>{t("executors.table.memory")}</th>
                    <th>{t("executors.table.labels")}</th>
                    <th className="text-right">{t("common.actions")}</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredExecutors.map((executor) => (
                    <tr key={executor.executorId} className="border-b last:border-0">
                      <td className="py-2">
                        <p className="font-medium">{executor.name}</p>
                        <p className="text-xs text-muted-foreground">{executor.executorId}</p>
                      </td>
                      <td>
                        <Badge variant={badgeVariant(executor.status)}>
                          {t(`executors.status.${executor.status.toLowerCase()}`)}
                        </Badge>
                      </td>
                      <td>
                        <span className="whitespace-nowrap">{formatPercent(executor.cpuUsage)}</span>
                      </td>
                      <td>
                        {executor.memoryUsageMb === null || executor.memoryUsageMb === undefined ? (
                          <span className="whitespace-nowrap">{formatGbFromMb(executor.memoryCapacityMb)} GB</span>
                        ) : (
                          <span className="whitespace-nowrap">
                            {formatGbFromMb(executor.memoryUsageMb)} / {formatGbFromMb(executor.memoryCapacityMb)} GB (
                            {formatPercent((executor.memoryUsageMb / executor.memoryCapacityMb) * 100)})
                          </span>
                        )}
                      </td>
                      <td>
                        <div className="flex flex-wrap gap-1">
                          {Object.entries(executor.labels).map(([key, value]) => (
                            <Badge key={key} variant="outline">
                              {key}:{value}
                            </Badge>
                          ))}
                        </div>
                      </td>
                      <td className="text-right">
                        <div className="flex flex-wrap justify-end gap-2">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => {
                              const nextStatus = executor.status === "DRAINING" ? "READY" : "DRAINING"
                              updateExecutorStatus(executor.executorId, nextStatus).then(() =>
                                executorsQuery.refetch(),
                              )
                            }}
                          >
                            {executor.status === "DRAINING"
                              ? t("executors.actions.resume")
                              : t("executors.actions.drain")}
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={tokenMutation.isPending && tokenDialog.open}
                            onClick={() => handleViewToken(executor)}
                          >
                            {t("executors.actions.viewToken")}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
      <RegisterDialog
        open={registerOpen}
        onOpenChange={(open) => setRegisterOpen(open)}
        form={registerForm}
        loading={registerMutation.isPending}
        onSubmit={(values) => {
          registerMutation.mutate({
            name: values.name,
            cpuCapacity: values.cpuCapacity,
            memoryCapacityMb: values.memoryCapacityMb,
            labels: parseLabels(values.labels),
          })
        }}
      />
      <ExecutorTokenDialog
        open={tokenDialog.open}
        executorName={tokenDialog.executorName}
        token={tokenDialog.token}
        error={tokenDialog.error}
        loading={tokenMutation.isPending}
        onOpenChange={(open) => {
          if (!open) {
            setTokenDialog({ open: false, executorName: "", token: "", error: null })
          }
        }}
      />
    </div>
  )
}

function parseLabels(input?: string) {
  if (!input) return undefined
  return Object.fromEntries(
    input
      .split(",")
      .map((pair) => pair.trim())
      .filter(Boolean)
      .map((pair) => {
        const [key, value] = pair.split("=")
        return [key, value ?? ""]
      }),
  )
}

function badgeVariant(status: ExecutorStatus) {
  switch (status) {
    case "READY":
      return "default"
    case "BUSY":
      return "secondary"
    case "DRAINING":
      return "secondary"
    case "OFFLINE":
      return "outline"
    default:
      return "secondary"
  }
}

type RegisterDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  form: ReturnType<typeof useForm<z.infer<typeof registerSchema>>>
  loading: boolean
  onSubmit: (values: z.infer<typeof registerSchema>) => void
}

function RegisterDialog({ open, onOpenChange, form, loading, onSubmit }: RegisterDialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("executors.dialogs.registerTitle")}</DialogTitle>
        </DialogHeader>
        <form
          className="space-y-4"
          onSubmit={form.handleSubmit((values) => onSubmit({ ...values, cpuCapacity: Number(values.cpuCapacity) }))}
        >
          <div className="space-y-2">
            <Label htmlFor="name">{t("executors.form.name")}</Label>
            <Input id="name" {...form.register("name")} />
          </div>
          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="cpuCapacity">{t("executors.form.cpu")}</Label>
              <Input type="number" id="cpuCapacity" {...form.register("cpuCapacity", { valueAsNumber: true })} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="memoryCapacityMb">{t("executors.form.memory")}</Label>
              <Input
                type="number"
                id="memoryCapacityMb"
                {...form.register("memoryCapacityMb", { valueAsNumber: true })}
              />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="labels">{t("executors.form.labels")}</Label>
            <Textarea id="labels" placeholder="zone=shanghai,arch=amd64" {...form.register("labels")} />
          </div>
          <Button className="w-full" type="submit" disabled={loading}>
            {loading ? t("common.saving") : t("common.save")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type TokenDialogProps = {
  open: boolean
  executorName: string
  token: string
  error: string | null
  loading: boolean
  onOpenChange: (open: boolean) => void
}

function ExecutorTokenDialog({ open, onOpenChange, executorName, token, error, loading }: TokenDialogProps) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    setCopied(false)
  }, [token, open])

  const handleCopy = async () => {
    if (!token) {
      return
    }
    try {
      await navigator.clipboard.writeText(token)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopied(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("executors.dialogs.tokenTitle", { name: executorName || "" })}</DialogTitle>
        </DialogHeader>
        <p className="text-sm text-muted-foreground">{t("executors.dialogs.tokenHint")}</p>
        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} />
            {t("common.loading")}
          </div>
        ) : error ? (
          <p className="text-sm text-destructive">{error}</p>
        ) : token ? (
          <div className="space-y-2">
            <Input readOnly value={token} />
            <div className="flex items-center gap-2">
              <Button type="button" onClick={handleCopy} variant="outline" size="sm">
                {copied ? t("executors.dialogs.tokenCopied") : t("executors.dialogs.tokenCopy")}
              </Button>
              {copied ? <span className="text-xs text-muted-foreground">{t("executors.dialogs.tokenCopied")}</span> : null}
            </div>
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">{t("executors.dialogs.tokenEmpty")}</p>
        )}
      </DialogContent>
    </Dialog>
  )
}
