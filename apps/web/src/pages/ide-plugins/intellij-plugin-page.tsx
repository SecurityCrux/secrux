import { useState } from "react"
import { useTranslation } from "react-i18next"
import { useMutation, useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Spinner } from "@/components/ui/spinner"
import { Textarea } from "@/components/ui/textarea"
import { formatDateTime } from "@/lib/utils"
import { createIntellijToken, listIntellijTokens, revokeIntellijToken } from "@/services/ide-plugin-service"
import type { IntellijTokenCreatedResponse } from "@/types/api"

type CopiedField = "token"

export function IntellijPluginPage() {
  const { t } = useTranslation()
  const [tokenName, setTokenName] = useState("")
  const [createdToken, setCreatedToken] = useState<IntellijTokenCreatedResponse | null>(null)
  const [revokingId, setRevokingId] = useState<string | null>(null)
  const [copied, setCopied] = useState<CopiedField | null>(null)
  const [error, setError] = useState<string | null>(null)

  const tokensQuery = useQuery({
    queryKey: ["idePlugins", "intellij", "tokens"],
    queryFn: listIntellijTokens,
  })
  const tokens = tokensQuery.data ?? []

  const createTokenMutation = useMutation({
    mutationFn: () => createIntellijToken({ name: tokenName || null }),
    onMutate: () => {
      setError(null)
    },
    onSuccess: (result) => {
      if (!result) {
        setError(t("idePlugins.intellij.create.createError"))
        return
      }
      setCreatedToken(result)
      setTokenName("")
      void tokensQuery.refetch()
    },
    onError: () => {
      setError(t("idePlugins.intellij.create.createError"))
    },
  })

  const revokeTokenMutation = useMutation({
    mutationFn: async (tokenId: string) => {
      await revokeIntellijToken(tokenId)
    },
    onMutate: (tokenId) => {
      setError(null)
      setRevokingId(tokenId)
    },
    onSettled: () => {
      setRevokingId(null)
      void tokensQuery.refetch()
    },
    onError: () => {
      setError(t("idePlugins.intellij.tokens.revokeError"))
    },
  })

  const copyText = async (value: string, field: CopiedField) => {
    if (!value) return
    setError(null)
    try {
      await navigator.clipboard.writeText(value)
      setCopied(field)
      window.setTimeout(() => setCopied((current) => (current === field ? null : current)), 1200)
    } catch {
      setError(t("idePlugins.intellij.copyError"))
    }
  }

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{t("idePlugins.intellij.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("idePlugins.intellij.subtitle")}</p>
      </div>

      {error ? <p className="text-sm text-destructive">{error}</p> : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t("idePlugins.intellij.create.title")}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="ide-plugin-token-name">{t("idePlugins.intellij.create.name")}</Label>
            <Input
              id="ide-plugin-token-name"
              value={tokenName}
              onChange={(event) => setTokenName(event.target.value)}
              placeholder={t("idePlugins.intellij.create.namePlaceholder")}
            />
          </div>

          <div className="flex gap-2">
            <Button type="button" onClick={() => void createTokenMutation.mutate()} disabled={createTokenMutation.isPending}>
              {createTokenMutation.isPending ? t("idePlugins.intellij.create.generating") : t("idePlugins.intellij.create.generate")}
            </Button>
          </div>

          {createdToken ? (
            <div className="space-y-2">
              <div className="flex items-center justify-between gap-2">
                <Label htmlFor="ide-plugin-token">{t("idePlugins.intellij.create.token")}</Label>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => void copyText(createdToken.token, "token")}
                  disabled={!createdToken.token}
                >
                  {copied === "token" ? t("common.copied") : t("common.copy")}
                </Button>
              </div>
              <Textarea id="ide-plugin-token" readOnly value={createdToken.token} className="font-mono text-xs" />
              <p className="text-xs text-muted-foreground">{t("idePlugins.intellij.create.tokenHint")}</p>
              <p className="text-xs text-muted-foreground">
                {t("idePlugins.intellij.create.createdAt")} {formatDateTime(createdToken.createdAt)}
              </p>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle className="text-base">{t("idePlugins.intellij.tokens.title")}</CardTitle>
          <Button type="button" variant="outline" onClick={() => void tokensQuery.refetch()} disabled={tokensQuery.isFetching}>
            {t("common.refresh")}
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          {tokensQuery.isLoading ? (
            <Spinner />
          ) : tokensQuery.isError ? (
            <p className="text-sm text-destructive">{t("idePlugins.intellij.tokens.loadError")}</p>
          ) : tokens.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("idePlugins.intellij.tokens.empty")}</p>
          ) : (
            <div className="divide-y rounded-md border">
              {tokens.map((token) => {
                const statusKey = token.revokedAt ? "revoked" : "active"
                return (
                  <div key={token.tokenId} className="flex flex-col gap-2 px-3 py-3 md:flex-row md:items-center md:justify-between">
                    <div className="space-y-1">
                      <p className="text-sm font-medium">{token.name || t("idePlugins.intellij.tokens.unnamed")}</p>
                      <p className="text-xs text-muted-foreground">
                        {t("idePlugins.intellij.tokens.tokenHintLabel")}{" "}
                        <span className="font-mono text-[11px]">{token.tokenHint}</span>
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {t("idePlugins.intellij.tokens.createdAt")} {formatDateTime(token.createdAt)} ·{" "}
                        {t("idePlugins.intellij.tokens.lastUsedAt")} {formatDateTime(token.lastUsedAt)}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {t("idePlugins.intellij.tokens.status")} {t(`idePlugins.intellij.tokens.${statusKey}`)}
                      </p>
                    </div>

                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        disabled={!!token.revokedAt || revokeTokenMutation.isPending}
                        onClick={() => void revokeTokenMutation.mutate(token.tokenId)}
                      >
                        {revokingId === token.tokenId && revokeTokenMutation.isPending
                          ? t("idePlugins.intellij.tokens.revoking")
                          : t("idePlugins.intellij.tokens.revoke")}
                      </Button>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </CardContent>
      </Card>

    </div>
  )
}
