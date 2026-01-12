# Architecture

Status: **Draft**

## Goals

- Use IntelliJ **PSI/UAST** to perform *static* code understanding for audit workflows.
- Provide a **fast UI loop** for: building call graphs → finding sinks → exploring call chains → recording audit notes.
- Persist and reload derived artifacts (call graph, sink matches, memo) to avoid recomputation.
- Integrate with **Secrux** by reporting call chains in **SARIF 2.1.0** (`codeFlows`) so Secrux can reuse existing findings + AI review pipeline.

## Non-goals (v1)

- Full interprocedural data-flow / taint analysis for all JVM languages.
- Soundness completeness (reflection, dynamic proxies, dependency injection dispatch).
- Supporting every framework entry-point pattern beyond Spring MVC-style controllers.

## UX Layout

The plugin uses **two Tool Windows**:

- **Left tool window**: *Secrux* (actions + navigation)
  - Buttons: Build CallGraph / Reload Graph / Clear Cache
  - Panels: Memo / Sinks / Entry Points / Settings / Platform
- **Bottom tool window**: *Secrux Results*
  - Tables: Sink matches, Call chains, Report history
  - Details panel: selected chain preview (steps + code snippet)

Editor integration:

- Right-click context menu actions on PSI/UAST targets:
  - “Add to Audit Memo”
  - “Mark as Sink (Local)”
  - “Search Callers / Search Callees”
  - “Report Call Chain to Secrux”

## Core Components

### CallGraph

**Responsibility**

- Build and maintain a **method-level directed graph**.
- Store **call site location** for edges when available.

**Key types (conceptual)**

- `MethodId`: stable identifier (FQN + signature + file path fallback)
- `MethodNode`: method metadata (name, class, file, range)
- `CallSite`: file + line/column + snippet hash (optional)
- `CallEdge`: `caller -> callee` with `CallSite`

**Build strategy**

- Traverse project source files via UAST.
- For each call expression:
  - Resolve target method when possible.
  - Record edge + call site.
  - If unresolved, record as “external/unknown” with best-effort label.

### Sink Registry + Sink Scan

**Sink definition** (planned)

- A sink matches **resolved** calls by:
  - `classFqn`
  - `methodName`
  - optional `parameterTypes`
  - optional language/module filters

Sink initialization:

- Load sink list from:
  - Built-in presets (bundled)
  - User file (YAML/JSON) per project

Scan results:

- List of `SinkMatch` items with:
  - sink id / category
  - file + location
  - enclosing method id

IDE highlighting:

- Use editor range highlighters to mark sink locations.
- Keep a registry to re-apply highlights when an editor opens.

### Call Chain Discovery

Call chains are computed from the call graph:

- Reverse search: `sinkEnclosingMethod <- callers <- ...`
- Controls:
  - max depth
  - max paths per sink
  - cycle handling
  - external nodes handling

Optional filter:

- Only show chains that reach at least one **Entry Point**.

### Entry Point Discovery (Spring Boot)

Entry points are detected by scanning for:

- `@RestController` / `@Controller` classes
- Request mapping annotations on methods:
  - `@RequestMapping`, `@GetMapping`, `@PostMapping`, etc.

Each entry point is recorded as:

- `EntryPoint(methodId, httpMapping?, file, location)`

### Audit Memo

Memo is a project-local list of pinned nodes:

- Stored as `SmartPsiElementPointer` (runtime)
- Persisted as stable `MemoItemId` (disk), e.g.:
  - `{ filePath, fqName, signature, textRangeHash }`

### Persistence

Two storage layers:

1. **Lightweight state** (settings, memo list, last scan time):
   - IntelliJ `PersistentStateComponent`
2. **Heavy artifacts** (call graph + sink index):
   - Stored under project directory (recommended):
     - `.idea/secrux/` or `.secrux/idea/`
   - Versioned file format:
     - `graph.formatVersion`
     - `projectFingerprint` (paths + module + git HEAD optional)

### Background Execution + Cancellation

- Build/scan operations run as cancellable background tasks.
- UAST/PSI access uses read actions and respects IDE indexing (Dumb Mode).

## Secrux Reporting (high-level)

The plugin produces a SARIF report per “audit finding”:

- One SARIF `result` per sink match (or per call chain).
- `codeFlows` encodes call chain steps (locations).
- Optional snippet embedding for each step:
  - either embed snippet in SARIF `region.snippet.text`
  - or upload only coordinates and let Secrux/executors resolve snippets from git/archives

Details: `secrux-intellij-plugin/docs/SECRUX_INTEGRATION.md`

## Known Limitations

- Static call graph is approximate (dynamic dispatch, reflection, Lombok, proxies).
- Kotlin/Java mixed projects may produce partially resolved nodes depending on indexing state.
- Very large projects require incremental build + caching to avoid long blocking.

