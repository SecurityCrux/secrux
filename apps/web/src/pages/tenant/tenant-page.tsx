import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Spinner } from "@/components/ui/spinner"
import { useAuth } from "@/hooks/use-auth"
import { fetchTenant, updateTenant } from "@/services/tenant-service"

export function TenantPage() {
  const { t } = useTranslation()
  const { user } = useAuth()
  const canManage = (user?.roles ?? []).includes("user:manage")

  const tenantQuery = useQuery({
    queryKey: ["tenant"],
    queryFn: fetchTenant,
    enabled: canManage,
  })

  const updateMutation = useMutation({
    mutationFn: updateTenant,
    onSuccess: () => void tenantQuery.refetch(),
  })

  const [name, setName] = useState("")
  const [contactEmail, setContactEmail] = useState("")

  useEffect(() => {
    if (!tenantQuery.data) return
    setName(tenantQuery.data.name ?? "")
    setContactEmail(tenantQuery.data.contactEmail ?? "")
  }, [tenantQuery.data])

  if (!canManage) {
    return <p className="text-sm text-muted-foreground">{t("tenant.accessDenied")}</p>
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{t("tenant.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("tenant.subtitle")}</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("tenant.cardTitle")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {tenantQuery.isLoading ? (
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <Spinner size={16} /> {t("common.loading")}
            </div>
          ) : tenantQuery.isError ? (
            <p className="text-sm text-destructive">{t("tenant.error")}</p>
          ) : tenantQuery.data ? (
            <>
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="space-y-2">
                  <Label htmlFor="tenant-name">{t("tenant.fields.name")}</Label>
                  <Input id="tenant-name" value={name} onChange={(e) => setName(e.target.value)} />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="tenant-email">{t("tenant.fields.contactEmail")}</Label>
                  <Input
                    id="tenant-email"
                    value={contactEmail}
                    onChange={(e) => setContactEmail(e.target.value)}
                    placeholder="ops@example.com"
                  />
                </div>
              </div>

              <div className="flex items-center justify-between rounded-md border bg-muted/20 px-3 py-2 text-xs text-muted-foreground">
                <span>
                  {t("tenant.fields.tenantId")}: <span className="font-mono">{tenantQuery.data.tenantId}</span>
                </span>
                <span>
                  {t("tenant.fields.plan")}: <span className="font-mono">{tenantQuery.data.plan}</span>
                </span>
              </div>

              <div className="flex justify-end">
                <Button
                  onClick={() =>
                    updateMutation.mutate({
                      name: name.trim(),
                      contactEmail: contactEmail.trim() || null,
                    })
                  }
                  disabled={updateMutation.isPending || name.trim().length === 0}
                >
                  {updateMutation.isPending ? t("common.saving") : t("common.save")}
                </Button>
              </div>
            </>
          ) : (
            <p className="text-sm text-muted-foreground">{t("tenant.empty")}</p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

