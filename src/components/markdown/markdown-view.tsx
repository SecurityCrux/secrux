import { useMemo, type ReactNode } from "react"

import { cn } from "@/lib/utils"

type MarkdownViewProps = {
  content?: string | null
  className?: string
}

type Block =
  | { type: "heading"; level: number; text: string }
  | { type: "paragraph"; text: string }
  | { type: "ul"; items: string[] }
  | { type: "ol"; items: { text: string; children: string[] }[] }
  | { type: "code"; language: string | null; content: string }
  | { type: "hr" }

export function MarkdownView({ content, className }: MarkdownViewProps) {
  const blocks = useMemo(() => parseMarkdown(content ?? ""), [content])
  if (!content?.trim()) {
    return <div className={cn("text-sm text-muted-foreground", className)}>—</div>
  }
  return (
    <div className={cn("space-y-3 text-sm leading-relaxed", className)}>
      {blocks.map((block, index) => (
        <div key={index}>{renderBlock(block)}</div>
      ))}
    </div>
  )
}

function renderBlock(block: Block): ReactNode {
  switch (block.type) {
    case "heading": {
      const level = Math.min(6, Math.max(1, block.level))
      const className =
        level === 1
          ? "text-xl font-semibold"
          : level === 2
            ? "text-lg font-semibold"
            : level === 3
              ? "text-base font-semibold"
              : "text-sm font-semibold"
      const Tag = level <= 2 ? "h2" : level === 3 ? "h3" : "h4"
      return <Tag className={cn("break-words", className)}>{renderInline(block.text)}</Tag>
    }
    case "paragraph":
      return (
        <p className="whitespace-pre-wrap break-words text-sm text-foreground/90">
          {renderInline(block.text)}
        </p>
      )
    case "ul":
      return (
        <ul className="list-disc space-y-1 pl-5">
          {block.items.map((item, idx) => (
            <li key={idx} className="break-words">
              {renderInline(item)}
            </li>
          ))}
        </ul>
      )
    case "ol":
      return (
        <ol className="list-decimal space-y-1 pl-5">
          {block.items.map((item, idx) => (
            <li key={idx} className="break-words">
              <div>{renderInline(item.text)}</div>
              {item.children.length > 0 ? (
                <ul className="mt-1 list-disc space-y-1 pl-5">
                  {item.children.map((child, childIdx) => (
                    <li key={childIdx} className="break-words">
                      {renderInline(child)}
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          ))}
        </ol>
      )
    case "code":
      return (
        <div className="space-y-2">
          {block.language ? (
            <div className="font-mono text-[10px] text-muted-foreground">{block.language}</div>
          ) : null}
          <pre className="overflow-x-auto rounded-md border bg-muted/20 p-3 text-xs">
            <code className="whitespace-pre font-mono">{block.content}</code>
          </pre>
        </div>
      )
    case "hr":
      return <hr className="border-border" />
  }
}

function parseMarkdown(input: string): Block[] {
  const text = input.replace(/\r\n/g, "\n")
  const lines = text.split("\n")
  const blocks: Block[] = []

  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (!line || line.trim() === "") {
      i += 1
      continue
    }

    if (line.trimStart().startsWith("```")) {
      const language = line.trim().slice(3).trim() || null
      i += 1
      const codeLines: string[] = []
      while (i < lines.length && !lines[i].trimStart().startsWith("```")) {
        codeLines.push(lines[i])
        i += 1
      }
      if (i < lines.length && lines[i].trimStart().startsWith("```")) {
        i += 1
      }
      blocks.push({ type: "code", language, content: codeLines.join("\n") })
      continue
    }

    const heading = /^(#{1,6})\s+(.*)$/.exec(line)
    if (heading) {
      blocks.push({ type: "heading", level: heading[1].length, text: heading[2] })
      i += 1
      continue
    }

    const hr = /^(-{3,}|\*{3,})$/.exec(line.trim())
    if (hr) {
      blocks.push({ type: "hr" })
      i += 1
      continue
    }

    const ul = /^\s*[-*]\s+(.*)$/.exec(line)
    if (ul) {
      const items: string[] = []
      while (i < lines.length) {
        const m = /^\s*[-*]\s+(.*)$/.exec(lines[i] ?? "")
        if (!m) break
        items.push(m[1])
        i += 1
      }
      blocks.push({ type: "ul", items })
      continue
    }

    const ol = /^\s*\d+\.\s+(.*)$/.exec(line)
    if (ol) {
      const items: { text: string; children: string[] }[] = []
      while (i < lines.length) {
        const m = /^\s*\d+\.\s+(.*)$/.exec(lines[i] ?? "")
        if (!m) break
        const item = { text: m[1], children: [] as string[] }
        i += 1
        while (i < lines.length) {
          const child = /^\s{2,}[-*]\s+(.*)$/.exec(lines[i] ?? "")
          if (!child) break
          item.children.push(child[1])
          i += 1
        }
        items.push(item)
      }
      blocks.push({ type: "ol", items })
      continue
    }

    const paragraphLines: string[] = []
    while (i < lines.length) {
      const current = lines[i] ?? ""
      if (current.trim() === "") break
      if (current.trimStart().startsWith("```")) break
      if (/^(#{1,6})\s+/.test(current)) break
      if (/^\s*[-*]\s+/.test(current)) break
      if (/^\s*\d+\.\s+/.test(current)) break
      if (/^(-{3,}|\*{3,})$/.test(current.trim())) break
      paragraphLines.push(current)
      i += 1
    }
    blocks.push({ type: "paragraph", text: paragraphLines.join("\n") })
  }

  return blocks
}

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let rest = text
  let key = 0

  const patterns = [
    { type: "link" as const, regex: /\[([^\]]+)\]\(([^)]+)\)/ },
    { type: "bold" as const, regex: /\*\*([^*]+)\*\*/ },
    { type: "code" as const, regex: /`([^`]+)`/ },
  ]

  while (rest.length > 0) {
    let best:
      | { type: (typeof patterns)[number]["type"]; match: RegExpExecArray; index: number }
      | null = null

    for (const pattern of patterns) {
      pattern.regex.lastIndex = 0
      const match = pattern.regex.exec(rest)
      if (!match) continue
      if (!best || match.index < best.index) {
        best = { type: pattern.type, match, index: match.index }
      }
    }

    if (!best) {
      nodes.push(rest)
      break
    }

    if (best.index > 0) {
      nodes.push(rest.slice(0, best.index))
    }

    const raw = best.match[0]
    const value = best.match[1] ?? ""
    if (best.type === "bold") {
      nodes.push(
        <strong key={key++} className="font-semibold">
          {value}
        </strong>
      )
    } else if (best.type === "code") {
      nodes.push(
        <code key={key++} className="rounded bg-muted px-1 py-0.5 font-mono text-[12px]">
          {value}
        </code>
      )
    } else if (best.type === "link") {
      const label = value
      const href = (best.match[2] ?? "").trim()
      const safeHref = isSafeHref(href) ? href : null
      nodes.push(
        safeHref ? (
          <a
            key={key++}
            href={safeHref}
            target="_blank"
            rel="noreferrer"
            className="underline decoration-muted-foreground/40 underline-offset-4 hover:decoration-muted-foreground"
          >
            {label}
          </a>
        ) : (
          <span key={key++}>{label}</span>
        )
      )
    }

    rest = rest.slice(best.index + raw.length)
  }

  return nodes
}

function isSafeHref(href: string): boolean {
  return href.startsWith("https://") || href.startsWith("http://")
}

