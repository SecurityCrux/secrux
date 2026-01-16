import { useMemo } from "react"
import { useTranslation } from "react-i18next"
import hljs from "highlight.js/lib/common"
import "highlight.js/styles/github.css"

import type { CodeLine, CodeSnippet } from "@/types/api"

export function CodeSnippetView({
  snippet,
  emptyText,
}: {
  snippet?: CodeSnippet | null
  emptyText?: string
}) {
  const { t } = useTranslation()
  if (!snippet) {
    return <p className="text-sm text-muted-foreground">{emptyText ?? t("findings.detailSection.noCode")}</p>
  }

  const language = guessLanguage(snippet.path)
  const highlightedLines = useMemo(() => {
    return snippet.lines.map((line) => {
      if (isGapLine(line)) {
        return { ...line, html: escapeHtml(line.content) }
      }
      if (!language) {
        return { ...line, html: escapeHtml(line.content) }
      }
      try {
        const { value } = hljs.highlight(line.content, { language, ignoreIllegals: true })
        return { ...line, html: value }
      } catch {
        return { ...line, html: escapeHtml(line.content) }
      }
    })
  }, [snippet.lines, language])

  return (
    <div className="secrux-code-snippet min-w-0 overflow-hidden rounded-md border border-border bg-muted/10">
      <div className="border-b border-border bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
        <div className="break-all">{snippet.path}</div>
        <div className="mt-1">
          {snippet.startLine} - {snippet.endLine}
        </div>
      </div>
      <div className="max-h-[440px] overflow-auto">
        {highlightedLines.map((line: CodeLine & { html: string }, idx) => {
          if (isGapLine(line)) {
            return (
              <div key={`gap-${idx}`} className="grid grid-cols-[64px,1fr] items-start gap-3 px-3 py-1 text-sm">
                <span className="text-right text-xs text-muted-foreground"></span>
                <code className="min-w-0 whitespace-pre-wrap break-words font-mono text-muted-foreground italic">…</code>
              </div>
            )
          }
          return (
            <div
              key={line.lineNumber}
              className={`grid grid-cols-[64px,1fr] items-start gap-3 px-3 py-1 text-sm ${line.highlight ? "bg-amber-50/70 dark:bg-amber-500/10" : ""}`}
            >
              <span className="text-right text-xs text-muted-foreground">{line.lineNumber}</span>
              <code
                className="hljs min-w-0 whitespace-pre-wrap break-words font-mono"
                dangerouslySetInnerHTML={{ __html: line.html }}
              />
            </div>
          )
        })}
      </div>
    </div>
  )
}

function isGapLine(line: CodeLine) {
  return line.lineNumber <= 0 && line.content.trim() === "…"
}

function escapeHtml(value: string) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;")
}

function guessLanguage(path: string): string | undefined {
  const lower = path.toLowerCase()
  if (lower.endsWith(".kt")) return "kotlin"
  if (lower.endsWith(".java")) return "java"
  if (lower.endsWith(".js")) return "javascript"
  if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript"
  if (lower.endsWith(".jsx")) return "jsx"
  if (lower.endsWith(".json")) return "json"
  if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml"
  if (lower.endsWith(".go")) return "go"
  if (lower.endsWith(".rs")) return "rust"
  if (lower.endsWith(".py")) return "python"
  if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "bash"
  if (lower.endsWith(".sql")) return "sql"
  return undefined
}
