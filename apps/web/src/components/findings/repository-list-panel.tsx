import { useMemo } from "react"
import { useTranslation } from "react-i18next"
import type { UseQueryResult } from "@tanstack/react-query"

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Spinner } from "@/components/ui/spinner"
import { cn } from "@/lib/utils"
import type { RepositoryResponse } from "@/types/api"

type Props = {
  repositoriesQuery: UseQueryResult<RepositoryResponse[]>
  selectedRepoId: string | null
  onSelectRepo: (repoId: string) => void
  titleKey?: string
}

export function RepositoryListPanel({
  repositoriesQuery,
  selectedRepoId,
  onSelectRepo,
  titleKey = "repositories.title",
}: Props) {
  const { t } = useTranslation()
  const repositories = useMemo(() => repositoriesQuery.data ?? [], [repositoriesQuery.data])

  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle className="text-base">{t(titleKey)}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {repositoriesQuery.isLoading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Spinner size={16} /> {t("common.loading")}
          </div>
        ) : repositoriesQuery.isError ? (
          <p className="text-sm text-destructive">{t("repositories.error", { defaultValue: "仓库列表加载失败。" })}</p>
        ) : repositories.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("repositories.empty")}</p>
        ) : (
          <div className="space-y-2 overflow-y-auto">
            {repositories.map((repo) => {
              const label = repo.remoteUrl ?? repo.repoId
              return (
                <button
                  type="button"
                  key={repo.repoId}
                  onClick={() => onSelectRepo(repo.repoId)}
                  className={cn(
                    "w-full rounded-md border px-3 py-2 text-left transition hover:bg-muted",
                    repo.repoId === selectedRepoId ? "border-primary bg-muted" : "border-border"
                  )}
                >
                  <p className="truncate text-sm font-semibold">{label}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {repo.sourceMode} • {repo.scmType ?? "—"}
                  </p>
                </button>
              )
            })}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

