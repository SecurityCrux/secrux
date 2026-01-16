import { useEffect, useState } from "react"
import { useTranslation } from "react-i18next"
import { useMutation } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Spinner } from "@/components/ui/spinner"
import { Textarea } from "@/components/ui/textarea"
import { useAiKnowledgeEntriesQuery } from "@/hooks/queries/use-ai-knowledge"
import {
  createAiKnowledgeEntry,
  deleteAiKnowledgeEntry,
  searchAiKnowledge,
  updateAiKnowledgeEntry,
} from "@/services/ai-integration-service"
import type { AiKnowledgeEntry, AiKnowledgeEntryPayload, AiKnowledgeSearchHit } from "@/types/api"

export function AiKnowledgePage() {
  const { t } = useTranslation()
  const entriesQuery = useAiKnowledgeEntriesQuery()
  const [dialogState, setDialogState] = useState<{ open: boolean; editing?: AiKnowledgeEntry | null }>({
    open: false,
  })
  const [searchResults, setSearchResults] = useState<AiKnowledgeSearchHit[]>([])
  const [searchError, setSearchError] = useState<string | null>(null)

  const searchMutation = useMutation({
    mutationFn: (payload: { query: string; limit: number; tags: string[] }) =>
      searchAiKnowledge(payload).then((results) => results ?? []),
    onSuccess: (results) => {
      setSearchResults(results ?? [])
      setSearchError(null)
    },
    onError: (err: unknown) => {
      setSearchResults([])
      setSearchError(err instanceof Error ? err.message : "unknown")
    },
  })

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{t("aiKnowledge.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("aiKnowledge.subtitle")}</p>
        </div>
        <Button onClick={() => setDialogState({ open: true, editing: null })}>{t("aiKnowledge.actions.create")}</Button>
      </div>

      <AiKnowledgeSearchCard
        results={searchResults}
        isSearching={searchMutation.isPending}
        error={searchError}
        onSearch={(payload) => searchMutation.mutate(payload)}
      />

      <AiKnowledgeListCard
        query={entriesQuery}
        onEdit={(entry) => setDialogState({ open: true, editing: entry })}
        onDeleted={() => void entriesQuery.refetch()}
      />

      <AiKnowledgeDialog
        open={dialogState.open}
        editing={dialogState.editing ?? undefined}
        onOpenChange={(open) => setDialogState({ open })}
        onSaved={() => void entriesQuery.refetch()}
      />
    </div>
  )
}

type AiKnowledgeListProps = {
  query: ReturnType<typeof useAiKnowledgeEntriesQuery>
  onEdit: (entry: AiKnowledgeEntry) => void
  onDeleted: () => void
}

function AiKnowledgeListCard({ query, onEdit, onDeleted }: AiKnowledgeListProps) {
  const { t } = useTranslation()
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("aiKnowledge.listTitle")}</CardTitle>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <div className="py-6 text-sm text-muted-foreground">
            <Spinner size={18} /> {t("common.loading")}
          </div>
        ) : query.isError ? (
          <p className="py-6 text-sm text-destructive">{t("aiKnowledge.error")}</p>
        ) : query.data && query.data.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="border-b text-muted-foreground">
                <tr>
                  <th className="py-2 text-left">{t("aiKnowledge.columns.title")}</th>
                  <th className="py-2 text-left">{t("aiKnowledge.columns.tags")}</th>
                  <th className="py-2 text-left">{t("aiKnowledge.columns.source")}</th>
                  <th className="py-2 text-left">{t("aiKnowledge.columns.updated")}</th>
                  <th className="py-2 text-left">{t("aiKnowledge.columns.actions")}</th>
                </tr>
              </thead>
              <tbody>
                {query.data.map((entry) => (
                  <tr key={entry.entryId} className="border-b last:border-b-0">
                    <td className="py-2 font-medium">{entry.title}</td>
                    <td className="py-2">
                      {entry.tags.length > 0 ? (
                        <div className="flex flex-wrap gap-2">
                          {entry.tags.map((tag) => (
                            <span key={tag} className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                              {tag}
                            </span>
                          ))}
                        </div>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="py-2 text-xs text-muted-foreground">
                      {entry.sourceUri ? (
                        <a className="text-primary underline" href={entry.sourceUri} target="_blank" rel="noreferrer">
                          {entry.sourceUri}
                        </a>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="py-2 text-xs text-muted-foreground">{new Date(entry.updatedAt).toLocaleString()}</td>
                    <td className="py-2 space-x-2">
                      <Button variant="ghost" size="sm" onClick={() => onEdit(entry)}>
                        {t("common.edit")}
                      </Button>
                      <DeleteKnowledgeButton entryId={entry.entryId} onDeleted={onDeleted} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="py-6 text-sm text-muted-foreground">{t("aiKnowledge.empty")}</p>
        )}
      </CardContent>
    </Card>
  )
}

function DeleteKnowledgeButton({ entryId, onDeleted }: { entryId: string; onDeleted: () => void }) {
  const { t } = useTranslation()
  const mutation = useMutation({
    mutationFn: () => deleteAiKnowledgeEntry(entryId),
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

type AiKnowledgeDialogProps = {
  open: boolean
  editing?: AiKnowledgeEntry
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}

function AiKnowledgeDialog({ open, editing, onOpenChange, onSaved }: AiKnowledgeDialogProps) {
  const { t } = useTranslation()
  const [form, setForm] = useState({
    title: "",
    tagsText: "",
    sourceUri: "",
    body: "",
  })
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (editing) {
      setForm({
        title: editing.title,
        tagsText: editing.tags.join(", "),
        sourceUri: editing.sourceUri ?? "",
        body: editing.body,
      })
    } else if (open) {
      setForm({
        title: "",
        tagsText: "",
        sourceUri: "",
        body: "",
      })
    }
    setError(null)
  }, [editing, open])

  const mutation = useMutation({
    mutationFn: (payload: AiKnowledgeEntryPayload) =>
      editing ? updateAiKnowledgeEntry(editing.entryId, payload) : createAiKnowledgeEntry(payload),
    onSuccess: () => {
      onSaved()
      onOpenChange(false)
    },
    onError: (err: unknown) => setError(err instanceof Error ? err.message : "unknown"),
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setError(null)
    if (!form.title.trim() || !form.body.trim()) {
      setError(t("aiKnowledge.dialog.errors.required"))
      return
    }
    const payload: AiKnowledgeEntryPayload = {
      title: form.title.trim(),
      body: form.body,
      sourceUri: form.sourceUri.trim() || undefined,
      tags: parseTags(form.tagsText),
    }
    mutation.mutate(payload)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{editing ? t("aiKnowledge.dialog.editTitle") : t("aiKnowledge.dialog.title")}</DialogTitle>
          <DialogDescription>{t("aiKnowledge.dialog.subtitle")}</DialogDescription>
        </DialogHeader>
        <form className="space-y-3" onSubmit={handleSubmit}>
          <div className="grid gap-3">
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiKnowledge.columns.title")}</label>
              <Input className="mt-1" value={form.title} onChange={(event) => setForm({ ...form, title: event.target.value })} />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiKnowledge.dialog.tagsLabel")}</label>
              <Input
                className="mt-1"
                value={form.tagsText}
                placeholder={t("aiKnowledge.dialog.tagsHint") ?? undefined}
                onChange={(event) => setForm({ ...form, tagsText: event.target.value })}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiKnowledge.dialog.sourceLabel")}</label>
              <Input
                className="mt-1"
                value={form.sourceUri}
                placeholder="https://..."
                onChange={(event) => setForm({ ...form, sourceUri: event.target.value })}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-muted-foreground">{t("aiKnowledge.dialog.bodyLabel")}</label>
              <Textarea
                className="mt-1 font-mono text-xs"
                rows={10}
                value={form.body}
                onChange={(event) => setForm({ ...form, body: event.target.value })}
              />
            </div>
          </div>
          {error ? <p className="text-sm text-destructive">{error}</p> : null}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? t("common.loading") : t("aiKnowledge.dialog.submit")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

type AiKnowledgeSearchCardProps = {
  results: AiKnowledgeSearchHit[]
  isSearching: boolean
  error: string | null
  onSearch: (payload: { query: string; limit: number; tags: string[] }) => void
}

function AiKnowledgeSearchCard({ results, isSearching, error, onSearch }: AiKnowledgeSearchCardProps) {
  const { t } = useTranslation()
  const [form, setForm] = useState({
    query: "",
    tagsText: "",
    limit: 5,
  })

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!form.query.trim()) {
      return
    }
    onSearch({
      query: form.query.trim(),
      limit: Number.isNaN(form.limit) ? 5 : form.limit,
      tags: parseTags(form.tagsText),
    })
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t("aiKnowledge.search.title")}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <form className="grid gap-3 md:grid-cols-3" onSubmit={handleSubmit}>
          <div className="md:col-span-2">
            <label className="text-xs font-medium text-muted-foreground">{t("aiKnowledge.search.queryLabel")}</label>
            <Input
              className="mt-1"
              value={form.query}
              placeholder={t("aiKnowledge.search.placeholder") ?? undefined}
              onChange={(event) => setForm({ ...form, query: event.target.value })}
            />
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("aiKnowledge.search.tagsLabel")}</label>
            <Input
              className="mt-1"
              value={form.tagsText}
              placeholder={t("aiKnowledge.search.tagsPlaceholder") ?? undefined}
              onChange={(event) => setForm({ ...form, tagsText: event.target.value })}
            />
          </div>
          <div>
            <label className="text-xs font-medium text-muted-foreground">{t("aiKnowledge.search.limitLabel")}</label>
            <Input
              className="mt-1"
              type="number"
              min={1}
              max={20}
              value={form.limit}
              onChange={(event) => setForm({ ...form, limit: Number(event.target.value) })}
            />
          </div>
          <div className="md:col-span-2 flex items-end gap-2">
            <Button type="submit" disabled={isSearching}>
              {isSearching ? t("common.loading") : t("aiKnowledge.search.submit")}
            </Button>
            {error ? <p className="text-xs text-destructive">{error}</p> : null}
          </div>
        </form>
        <div className="space-y-2">
          <p className="text-sm font-medium">{t("aiKnowledge.search.results")}</p>
          {isSearching ? (
            <div className="text-sm text-muted-foreground">
              <Spinner size={16} /> {t("common.loading")}
            </div>
          ) : results.length > 0 ? (
            <div className="space-y-3">
              {results.map((hit) => (
                <div key={`${hit.entryId}-${hit.score}`} className="rounded-md border p-3">
                  <div className="flex items-center justify-between">
                    <p className="font-medium">{hit.title}</p>
                    <span className="text-xs text-muted-foreground">
                      {t("aiKnowledge.search.score", { score: hit.score.toFixed(2) })}
                    </span>
                  </div>
                  <p className="mt-1 text-sm text-muted-foreground">{hit.snippet}</p>
                  {hit.tags.length > 0 ? (
                    <div className="mt-2 flex flex-wrap gap-2">
                      {hit.tags.map((tag) => (
                        <span key={tag} className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                          {tag}
                        </span>
                      ))}
                    </div>
                  ) : null}
                </div>
              ))}
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("aiKnowledge.search.empty")}</p>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function parseTags(raw: string): string[] {
  return raw
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean)
}
