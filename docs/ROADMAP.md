# Roadmap

Status: **Draft**

## Phase 0 — Project bootstrap

- Repo/module scaffolding (Gradle, plugin.xml, runIde)
- Tool window skeleton (left + bottom)
- Settings storage (PersistentStateComponent + PasswordSafe)

## Phase 1 — Audit memo MVP

- “Add to Audit Memo” editor action
- Memo list UI + navigation
- Stable memo persistence

## Phase 2 — Call graph MVP (UAST)

- Build call graph button + progress + cancellation
- In-memory graph
- Save/reload graph artifacts

## Phase 3 — Sink scan + highlight

- Sink definition file format + presets
- Project scan producing sink matches
- Editor highlighting + results table + navigation

## Phase 4 — Call chain discovery

- Reverse search call chains with limits
- Entry-point detection (Spring controllers)
- Filter call chains “must reach entry point”

## Phase 5 — Secrux integration

- Secrux connection settings (baseUrl/tenant/project/task)
- SARIF generator (`codeFlows`) for call chains
- Upload flow + report history

## Phase 6 — AI review loop

- Trigger AI review via Secrux API
- Poll review status + show summary in IDE

## Phase 7 — Quality and scale

- Incremental graph rebuild (per file/module changes)
- Better resolution heuristics for dynamic dispatch
- Performance profiling + caches + limits tuning

