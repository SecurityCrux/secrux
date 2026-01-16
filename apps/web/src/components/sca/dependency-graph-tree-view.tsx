import { useMemo, useState } from "react"
import { useTranslation } from "react-i18next"
import { ChevronDown, ChevronRight } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import type { DependencyGraph, DependencyGraphEdge, DependencyGraphNode } from "@/types/api"

const PATH_SEP = "\u001f"

type RiskSeverity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO"

const SEVERITY_RANK: Record<RiskSeverity, number> = {
  CRITICAL: 5,
  HIGH: 4,
  MEDIUM: 3,
  LOW: 2,
  INFO: 1,
}

const SEVERITY_TEXT_CLASS: Record<RiskSeverity, string> = {
  CRITICAL: "text-red-600 dark:text-red-300",
  HIGH: "text-orange-600 dark:text-orange-300",
  MEDIUM: "text-amber-600 dark:text-amber-300",
  LOW: "text-sky-600 dark:text-sky-300",
  INFO: "text-slate-600 dark:text-slate-300",
}

function normalizeSeverity(raw: unknown): RiskSeverity | null {
  if (typeof raw !== "string") return null
  const value = raw.trim().toUpperCase()
  if (value === "CRITICAL" || value === "HIGH" || value === "MEDIUM" || value === "LOW" || value === "INFO") {
    return value
  }
  return null
}

function maxSeverity(a: RiskSeverity | null, b: RiskSeverity | null): RiskSeverity | null {
  if (!a) return b
  if (!b) return a
  return SEVERITY_RANK[a] >= SEVERITY_RANK[b] ? a : b
}

export function DependencyGraphTreeView({
  graph,
  riskByNodeId,
}: {
  graph: DependencyGraph
  riskByNodeId?: Record<string, string> | null
}) {
  const { t } = useTranslation()
  const [expandedPaths, setExpandedPaths] = useState<Set<string>>(() => new Set())
  const [selectedPath, setSelectedPath] = useState<string | null>(null)

  const nodesById = useMemo(() => new Map(graph.nodes.map((node) => [node.id, node])), [graph.nodes])

  const { outgoing, incoming } = useMemo(() => {
    const out = new Map<string, string[]>()
    const inc = new Map<string, string[]>()
    for (const node of graph.nodes) {
      out.set(node.id, [])
      inc.set(node.id, [])
    }
    for (const edge of graph.edges) {
      if (!out.has(edge.source)) out.set(edge.source, [])
      if (!inc.has(edge.target)) inc.set(edge.target, [])
      out.get(edge.source)!.push(edge.target)
      inc.get(edge.target)!.push(edge.source)
    }
    return { outgoing: out, incoming: inc }
  }, [graph.nodes, graph.edges])

  const rootIds = useMemo(() => {
    if (graph.nodes.length === 0) return []
    const inDegree = new Map<string, number>()
    const outDegree = new Map<string, number>()
    for (const node of graph.nodes) {
      inDegree.set(node.id, 0)
      outDegree.set(node.id, 0)
    }
    for (const edge of graph.edges) {
      inDegree.set(edge.target, (inDegree.get(edge.target) ?? 0) + 1)
      outDegree.set(edge.source, (outDegree.get(edge.source) ?? 0) + 1)
      if (!inDegree.has(edge.source)) inDegree.set(edge.source, 0)
      if (!outDegree.has(edge.target)) outDegree.set(edge.target, 0)
    }
    const candidates = graph.nodes.map((node) => node.id).filter((id) => (inDegree.get(id) ?? 0) === 0)
    const roots = candidates.length > 0 ? candidates : graph.nodes.map((node) => node.id)
    return roots.sort((a, b) => (outDegree.get(b) ?? 0) - (outDegree.get(a) ?? 0))
  }, [graph.nodes, graph.edges])

  const selectedNodeId = useMemo(() => {
    if (!selectedPath) return null
    const parts = selectedPath.split(PATH_SEP)
    const leaf = parts[parts.length - 1]
    return leaf || null
  }, [selectedPath])

  const selectedNode = selectedNodeId ? nodesById.get(selectedNodeId) ?? null : null
  const selectedDeps = selectedNodeId ? outgoing.get(selectedNodeId) ?? [] : []
  const selectedParents = selectedNodeId ? incoming.get(selectedNodeId) ?? [] : []

  const selfSeverityByNodeId = useMemo(() => {
    const map = new Map<string, RiskSeverity>()
    if (!riskByNodeId) return map
    for (const [nodeId, raw] of Object.entries(riskByNodeId)) {
      const severity = normalizeSeverity(raw)
      if (severity) map.set(nodeId, severity)
    }
    return map
  }, [riskByNodeId])

  const subtreeSeverityByNodeId = useMemo(() => {
    const memo = new Map<string, RiskSeverity | null>()
    const visiting = new Set<string>()

    const compute = (nodeId: string): RiskSeverity | null => {
      if (memo.has(nodeId)) return memo.get(nodeId) ?? null
      if (visiting.has(nodeId)) {
        const cyc = selfSeverityByNodeId.get(nodeId) ?? null
        memo.set(nodeId, cyc)
        return cyc
      }

      visiting.add(nodeId)
      let current = selfSeverityByNodeId.get(nodeId) ?? null
      const children = outgoing.get(nodeId) ?? []
      for (const childId of children) {
        current = maxSeverity(current, compute(childId))
      }
      visiting.delete(nodeId)
      memo.set(nodeId, current)
      return current
    }

    for (const node of graph.nodes) {
      compute(node.id)
    }
    return memo
  }, [graph.nodes, outgoing, selfSeverityByNodeId])

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <Badge variant="secondary">
          {t("sca.dependencyGraph.counts", { nodes: graph.nodes.length, edges: graph.edges.length })}
        </Badge>
      </div>

      <div className="grid gap-3 md:grid-cols-[1fr,360px]">
        <div className="min-w-0 rounded-md border">
          <div className="max-h-96 overflow-auto p-2">
            {rootIds.length === 0 ? (
              <p className="p-3 text-sm text-muted-foreground">{t("sca.dependencyGraph.empty")}</p>
            ) : (
              <div className="space-y-1">
                {rootIds.map((rootId) => (
                  <TreeNodeRow
                    key={rootId}
                    nodeId={rootId}
                    path={rootId}
                    depth={0}
                    ancestors={new Set()}
                    nodesById={nodesById}
                    outgoing={outgoing}
                    selfSeverityByNodeId={selfSeverityByNodeId}
                    subtreeSeverityByNodeId={subtreeSeverityByNodeId}
                    expandedPaths={expandedPaths}
                    onToggle={(path) => {
                      setExpandedPaths((prev) => {
                        const next = new Set(prev)
                        if (next.has(path)) next.delete(path)
                        else next.add(path)
                        return next
                      })
                    }}
                    selectedPath={selectedPath}
                    onSelect={setSelectedPath}
                    t={t}
                  />
                ))}
              </div>
            )}
          </div>
        </div>

        <div className="min-w-0 rounded-md border p-3 max-h-96 overflow-auto">
          {selectedNode ? (
            <div className="space-y-3">
              <div className="break-all text-sm font-semibold">{selectedNode.label}</div>
              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">{t("sca.dependencyGraph.directDeps")}</div>
                <EdgeList
                  edges={selectedDeps.map((id) => ({ source: selectedNodeId!, target: id }))}
                  nodesById={nodesById}
                />
              </div>
              <div className="space-y-2">
                <div className="text-xs font-medium text-muted-foreground">{t("sca.dependencyGraph.directDependents")}</div>
                <EdgeList
                  edges={selectedParents.map((id) => ({ source: id, target: selectedNodeId! }))}
                  nodesById={nodesById}
                />
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">{t("sca.dependencyGraph.selectNode")}</p>
          )}
        </div>
      </div>
    </div>
  )
}

function TreeNodeRow({
  nodeId,
  path,
  depth,
  ancestors,
  nodesById,
  outgoing,
  expandedPaths,
  onToggle,
  selectedPath,
  onSelect,
  selfSeverityByNodeId,
  subtreeSeverityByNodeId,
  t,
}: {
  nodeId: string
  path: string
  depth: number
  ancestors: Set<string>
  nodesById: Map<string, DependencyGraphNode>
  outgoing: Map<string, string[]>
  selfSeverityByNodeId: Map<string, RiskSeverity>
  subtreeSeverityByNodeId: Map<string, RiskSeverity | null>
  expandedPaths: Set<string>
  onToggle: (path: string) => void
  selectedPath: string | null
  onSelect: (path: string) => void
  t: (key: string, options?: Record<string, unknown>) => string
}) {
  const node = nodesById.get(nodeId)
  const children = outgoing.get(nodeId) ?? []
  const hasChildren = children.length > 0
  const isExpanded = expandedPaths.has(path)
  const isSelected = selectedPath === path

  const nextAncestors = useMemo(() => {
    const set = new Set(ancestors)
    set.add(nodeId)
    return set
  }, [ancestors, nodeId])

  const outCount = children.length
  const inCycle = ancestors.has(nodeId)
  const markerSeverity = isExpanded
    ? (selfSeverityByNodeId.get(nodeId) ?? null)
    : (subtreeSeverityByNodeId.get(nodeId) ?? null)

  return (
    <div className="min-w-0">
      <div
        className={cn(
          "flex min-w-0 items-start gap-1 rounded-md px-1 py-1",
          isSelected ? "bg-primary/5" : "hover:bg-muted/30"
        )}
        style={{ paddingLeft: depth * 16 }}
      >
        {hasChildren && !inCycle ? (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="h-7 w-7 shrink-0"
            aria-label={isExpanded ? t("sca.dependencyGraph.collapse") : t("sca.dependencyGraph.expand")}
            onClick={(event) => {
              event.stopPropagation()
              onToggle(path)
            }}
          >
            {isExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </Button>
        ) : (
          <div className="h-7 w-7 shrink-0" />
        )}

        <button
          type="button"
          className="min-w-0 flex-1 text-left"
          onClick={() => {
            onSelect(path)
            if (hasChildren && !inCycle && !expandedPaths.has(path)) {
              onToggle(path)
            }
          }}
        >
          <div className="min-w-0">
            <div
              className={cn(
                "break-all text-sm font-medium",
                markerSeverity ? SEVERITY_TEXT_CLASS[markerSeverity] : null
              )}
              title={markerSeverity ? t(`severities.${markerSeverity}`, { defaultValue: markerSeverity }) : undefined}
            >
              {node?.label ?? nodeId}
            </div>
            <div className="mt-1 flex flex-wrap gap-2 text-xs text-muted-foreground">
              <span>{t("sca.dependencyGraph.deps", { count: outCount })}</span>
              {inCycle ? <Badge variant="outline">{t("sca.dependencyGraph.cycle")}</Badge> : null}
            </div>
            {node?.purl ? <div className="mt-1 break-all text-xs text-muted-foreground">{node.purl}</div> : null}
          </div>
        </button>
      </div>

      {hasChildren && !inCycle && isExpanded ? (
        <div className="space-y-1">
          {children.map((childId) => {
            const childPath = `${path}${PATH_SEP}${childId}`
            return (
              <TreeNodeRow
                key={childPath}
                nodeId={childId}
                path={childPath}
                depth={depth + 1}
                ancestors={nextAncestors}
                nodesById={nodesById}
                outgoing={outgoing}
                selfSeverityByNodeId={selfSeverityByNodeId}
                subtreeSeverityByNodeId={subtreeSeverityByNodeId}
                expandedPaths={expandedPaths}
                onToggle={onToggle}
                selectedPath={selectedPath}
                onSelect={onSelect}
                t={t}
              />
            )
          })}
        </div>
      ) : null}
    </div>
  )
}

function EdgeList({
  edges,
  nodesById,
}: {
  edges: DependencyGraphEdge[]
  nodesById: Map<string, DependencyGraphNode>
}) {
  const { t } = useTranslation()
  if (edges.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("sca.dependencyGraph.none")}</p>
  }
  return (
    <div className="space-y-2">
      <div className="space-y-2 max-h-56 overflow-auto pr-1">
        {edges.slice(0, 30).map((edge, idx) => {
          const source = nodesById.get(edge.source)
          const target = nodesById.get(edge.target)
          return (
            <div
              key={`${edge.source}-${edge.target}-${idx}`}
              className="min-w-0 rounded-md border bg-muted/20 px-3 py-2 text-sm"
            >
              <div className="break-all">{source?.label ?? edge.source}</div>
              <div className="my-1 text-xs text-muted-foreground">↓</div>
              <div className="break-all">{target?.label ?? edge.target}</div>
            </div>
          )
        })}
      </div>
      {edges.length > 30 ? (
        <p className="text-xs text-muted-foreground">{t("sca.dependencyGraph.more", { count: edges.length - 30 })}</p>
      ) : null}
    </div>
  )
}
