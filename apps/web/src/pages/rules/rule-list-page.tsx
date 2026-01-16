import { z } from "zod"
import { zodResolver } from "@hookform/resolvers/zod"
import { useForm } from "react-hook-form"
import { useState } from "react"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Spinner } from "@/components/ui/spinner"
import { useRulesQuery } from "@/hooks/queries/use-rules"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { createRule } from "@/services/rule-service"
import type { RuleUpsertPayload } from "@/types/api"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

const severities = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"] as const

const formSchema = z.object({
  scope: z.string().min(1),
  key: z.string().min(1),
  name: z.string().min(1),
  engine: z.string().min(1),
  langs: z.string().min(1),
  severityDefault: z.enum(severities),
  tags: z.string().optional(),
  pattern: z.string().min(2),
  docs: z.string().optional(),
  enabled: z.enum(["true", "false"]),
  hash: z.string().min(1),
  signature: z.string().optional(),
})

type FormValues = z.infer<typeof formSchema>

export function RuleListPage() {
  const { t } = useTranslation()
  const rulesQuery = useRulesQuery()
  const [createOpen, setCreateOpen] = useState(false)

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("rules.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("rules.subtitle")}</p>
        </div>
        <Button onClick={() => setCreateOpen(true)}>{t("rules.actions.create")}</Button>
      </div>
      <RuleCreateDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={() => {
          void rulesQuery.refetch()
        }}
      />
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("rules.title")}</CardTitle>
        </CardHeader>
        <CardContent>
          {rulesQuery.isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Spinner size={18} /> {t("common.loading")}
            </div>
          ) : rulesQuery.isError ? (
            <p className="text-sm text-destructive">{t("rules.error")}</p>
          ) : rulesQuery.data && rulesQuery.data.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="py-2 text-left">{t("rules.columns.name")}</th>
                    <th className="py-2 text-left">{t("rules.columns.engine")}</th>
                    <th className="py-2 text-left">{t("rules.columns.severity")}</th>
                    <th className="py-2 text-left">{t("rules.columns.scope")}</th>
                    <th className="py-2 text-left">{t("rules.columns.langs")}</th>
                    <th className="py-2 text-left">{t("rules.columns.tags")}</th>
                    <th className="py-2 text-left">{t("rules.columns.enabled")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rulesQuery.data.map((rule) => (
                    <tr key={rule.ruleId} className="border-b last:border-b-0">
                      <td className="py-3">
                        <div className="font-medium">{rule.name}</div>
                        <div className="text-xs text-muted-foreground font-mono">{rule.ruleId}</div>
                      </td>
                      <td className="py-3">{rule.engine}</td>
                      <td className="py-3">{rule.severityDefault}</td>
                      <td className="py-3">
                        <span className="rounded bg-muted px-2 py-1 text-xs font-mono">{rule.scope}</span>
                      </td>
                      <td className="py-3">
                        {rule.langs.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {rule.langs.map((lang) => (
                              <Badge key={lang} variant="secondary">
                                {lang}
                              </Badge>
                            ))}
                          </div>
                        ) : (
                          "—"
                        )}
                      </td>
                      <td className="py-3">
                        {rule.tags.length > 0 ? (
                          <div className="flex flex-wrap gap-1">
                            {rule.tags.map((tag) => (
                              <Badge key={tag} variant="outline">
                                {tag}
                              </Badge>
                            ))}
                          </div>
                        ) : (
                          "—"
                        )}
                      </td>
                      <td className="py-3">
                        <Badge variant={rule.enabled ? "secondary" : "outline"}>
                          {rule.enabled ? "ON" : "OFF"}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("rules.empty")}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

type RuleCreateDialogProps = {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: () => void
}

function RuleCreateDialog({ open, onOpenChange, onCreated }: RuleCreateDialogProps) {
  const { t } = useTranslation()
  const [serverError, setServerError] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { isSubmitting, errors },
  } = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: {
      severityDefault: "HIGH",
      enabled: "true",
    },
  })

  const onSubmit = async (values: FormValues) => {
    setServerError(null)
    setSuccessMessage(null)

    let pattern: Record<string, unknown>
    let docs: Record<string, unknown> | undefined
    try {
      pattern = JSON.parse(values.pattern)
    } catch {
      setError("pattern", { message: t("rules.form.invalidJson") })
      return
    }
    if (values.docs && values.docs.trim().length > 0) {
      try {
        docs = JSON.parse(values.docs)
      } catch {
        setError("docs", { message: t("rules.form.invalidJson") })
        return
      }
    }

    const payload: RuleUpsertPayload = {
      scope: values.scope,
      key: values.key,
      name: values.name,
      engine: values.engine,
      langs: values.langs.split(",").map((lang) => lang.trim()).filter(Boolean),
      severityDefault: values.severityDefault,
      tags: values.tags ? values.tags.split(",").map((tag) => tag.trim()).filter(Boolean) : [],
      pattern,
      docs,
      enabled: values.enabled === "true",
      hash: values.hash,
      signature: values.signature || undefined,
    }

    try {
      await createRule(payload)
      setSuccessMessage(t("rules.form.success"))
      reset({
        severityDefault: "HIGH",
        enabled: "true",
      })
      onCreated()
      onOpenChange(false)
    } catch (error) {
      const message = error instanceof Error ? error.message : "unknown"
      setServerError(t("rules.form.serverError", { message }))
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl">
        <DialogHeader>
          <DialogTitle>{t("rules.form.title")}</DialogTitle>
          <DialogDescription>{t("rules.form.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="grid gap-4 md:grid-cols-2" onSubmit={handleSubmit(onSubmit)}>
          <FormField label={t("rules.form.scope")} error={errors.scope?.message}>
            <Input {...register("scope")} />
          </FormField>
          <FormField label={t("rules.form.key")} error={errors.key?.message}>
            <Input {...register("key")} />
          </FormField>
          <FormField label={t("rules.form.name")} error={errors.name?.message}>
            <Input {...register("name")} />
          </FormField>
          <FormField label={t("rules.form.engine")} error={errors.engine?.message}>
            <Input {...register("engine")} />
          </FormField>
          <FormField label={t("rules.form.langs")} error={errors.langs?.message}>
            <Input {...register("langs")} placeholder="java, kotlin" />
          </FormField>
          <FormField label={t("rules.form.severity")} error={errors.severityDefault?.message}>
            <select
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              {...register("severityDefault")}
            >
              {severities.map((severity) => (
                <option value={severity} key={severity}>
                  {severity}
                </option>
              ))}
            </select>
          </FormField>
          <FormField label={t("rules.form.tags")} error={errors.tags?.message}>
            <Input {...register("tags")} placeholder="security,appsec" />
          </FormField>
          <FormField label={t("rules.form.enabled")} error={errors.enabled?.message}>
            <select
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              {...register("enabled")}
            >
              <option value="true">ON</option>
              <option value="false">OFF</option>
            </select>
          </FormField>
          <FormField label={t("rules.form.hash")} error={errors.hash?.message}>
            <Input {...register("hash")} />
          </FormField>
          <FormField label={t("rules.form.signature")} error={errors.signature?.message}>
            <Input {...register("signature")} />
          </FormField>
          <FormField className="md:col-span-2" label={t("rules.form.pattern")} error={errors.pattern?.message}>
            <Textarea rows={6} {...register("pattern")} placeholder='{"pattern": "rule"}' />
          </FormField>
          <FormField className="md:col-span-2" label={t("rules.form.docs")} error={errors.docs?.message}>
            <Textarea rows={4} {...register("docs")} placeholder='{"description": "doc"}' />
          </FormField>
          {serverError ? (
            <p className="md:col-span-2 text-sm text-destructive">{serverError}</p>
          ) : successMessage ? (
            <p className="md:col-span-2 text-sm text-green-600">{successMessage}</p>
          ) : null}
          <div className="md:col-span-2">
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                {t("projects.actions.cancel")}
              </Button>
              <Button type="submit" disabled={isSubmitting}>
                {isSubmitting ? t("common.loading") : t("rules.form.submit")}
              </Button>
            </DialogFooter>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type FormFieldProps = {
  label: string
  error?: string
  children: React.ReactNode
  className?: string
}

function FormField({ label, error, children, className }: FormFieldProps) {
  return (
    <div className={className}>
      <label className="block text-sm font-medium text-foreground">{label}</label>
      <div className="mt-1">{children}</div>
      {error ? <p className="mt-1 text-xs text-destructive">{error}</p> : null}
    </div>
  )
}

