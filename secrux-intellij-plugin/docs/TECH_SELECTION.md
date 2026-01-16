# Tech Selection

Status: **Draft**

This document records key technical choices for implementing the Secrux IntelliJ Plugin (targeting IntelliJ IDEA **2025.2+**).

## Constraints

- Must build against IntelliJ IDEA **2025.2+** using the *new* IntelliJ plugin build approach (Gradle + Kotlin DSL).
- Prefer **minimal external dependencies** (reduce conflicts with IDE classpath and simplify distribution).
- Must use **PSI/UAST** as the core parsing layer (Java/Kotlin projects).
- Must support cancellable background computation and avoid blocking UI threads.

## Build System

### Options considered

- Gradle IntelliJ Plugin (legacy `org.jetbrains.intellij`)
- IntelliJ Platform Gradle Plugin (new `org.jetbrains.intellij.platform` / “IntelliJ Platform Plugin Template” style)

### Decision

Use **IntelliJ Platform Gradle Plugin** and follow the official **IntelliJ Platform Plugin Template** structure.

Reasons:

- Aligns with current/future IntelliJ releases (including 2025.2+).
- Better long-term support for platform dependencies, verifier, signing, publishing.
- Kotlin DSL-first.

## Language

Decision: **Kotlin**

Reasons:

- First-class support for IntelliJ plugin development.
- Works well with UI DSL and platform APIs.
- Easier to implement persistent state and data model serialization.

## Code Parsing (PSI/UAST)

Decision: **UAST-first**, PSI fallback.

- Primary traversal uses `org.jetbrains.uast.*` so Java/Kotlin share a single visitor pipeline.
- When UAST resolution is incomplete, fallback to:
  - Java PSI (`PsiMethod`, `PsiCallExpression`)
  - element pointers (`SmartPsiElementPointer`) for navigation stability.

Notes:

- Call resolution relies on `UCallExpression.resolve()` / `PsiReference.resolve()`.
- During indexing (Dumb Mode), analysis is delayed or partial; UI must reflect that.

## Call Graph Representation

Decision: custom in-memory adjacency lists (no heavy graph library).

- `Map<MethodId, MethodNode>`
- `Map<MethodId, List<CallEdge>>` (outgoing)
- `Map<MethodId, List<CallEdge>>` (incoming) for reverse search

Reasons:

- Small, predictable overhead.
- Easier to persist and version.
- Avoids shipping extra graph dependencies.

## Call Chain Search Algorithm

Decision: bounded reverse DFS/BFS with path enumeration.

Controls (configurable):

- `maxDepth`
- `maxPathsPerSink`
- `maxNodesPerPath`
- cycle detection

Reason:

- The number of paths can explode; hard limits are required for UX and stability.

## UI Toolkit

Decision: IntelliJ Platform UI components + Kotlin UI DSL.

- Tool windows: left (*Secrux*) and bottom (*Secrux Results*).
- Tables: `JBTable` / `TableView`
- Trees: `SimpleTree` / `JTree` with speed search

Reasons:

- Consistent with IDE look-and-feel.
- Works across Community/Ultimate.

## Highlighting Strategy

Decision: runtime editor highlighters applied by scan results.

- Use `RangeHighlighter` via editor `MarkupModel`.
- Maintain a project-level registry of highlights per `VirtualFile`.

Reason:

- Avoid always-on inspection overhead for v1.

Future:

- Optional `LocalInspectionTool` implementation to provide continuous highlights and quick fixes.

## Persistence

Decision: split between IntelliJ persistent state and project-local artifact files.

- `PersistentStateComponent`: settings, sink list selection, memo items, last run metadata.
- Disk artifacts: call graph and sink index stored under `.idea/secrux/` (or `.secrux/idea/`).

Reasons:

- Stable across restarts.
- Allows “reload without recompute”.

## HTTP + Authentication (Secrux integration)

Decision:

- HTTP: IntelliJ `HttpRequests` (respects IDE proxy, certificates, and common settings).
- Secrets: IntelliJ `PasswordSafe` for tokens/refresh tokens.
- Auth flow (recommended): OIDC Authorization Code + PKCE using system browser (with loopback redirect).

Details: `SECRUX_INTEGRATION.md`

## SARIF Generation

Decision: generate SARIF 2.1.0 JSON directly (minimal model).

- Keep a small set of SARIF fields needed by Secrux:
  - `runs[].tool.driver.name`
  - `runs[].results[]` with `locations` and `codeFlows`
- Encode call chains as `codeFlows.threadFlows.locations[]`

Reason:

- Avoid a large SARIF dependency and keep control over output shape.

## Testing

Decision:

- Unit test: pure Kotlin modules (graph build helpers, sink matcher, SARIF generator).
- Integration test: IntelliJ test framework for PSI/UAST-based parsing on fixture projects.

## Compatibility Notes

- Target JBR/JDK used by IntelliJ 2025.2+ (likely Java 21).
- Avoid depending on Ultimate-only APIs so the plugin can run on Community where possible.

