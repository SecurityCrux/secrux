# Secrux IntelliJ Plugin

Static code-audit assistant for Secrux, built on IntelliJ PSI/UAST.

This directory currently contains design + tech-selection documents. Implementation will follow these specs.

## Build & Run (dev)

Requirements:

- JDK 21
- Network access (Gradle will download IntelliJ SDK + dependencies)

Commands:

- Run IDE with the plugin: `cd apps/intellij-plugin && ../gradle-8.10.2/bin/gradle runIde`
- Build plugin ZIP: `cd apps/intellij-plugin && ../gradle-8.10.2/bin/gradle buildPlugin`

## Current MVP

- Tool windows: **Secrux** (left) and **Secrux Results** (bottom)
- Audit memo: right-click → **Add to audit memo** → double-click to navigate
- Sink scan (main sources only): click **Scan sinks** → highlights matches + lists them in the results table
- Navigation: double-click a row to jump to the code location
- Call graph: **Build call graph** (persisted to `.idea/secrux/callgraph.json`), supports reload/clear
- Call chains: select rows → **Show call chains** → double-click a step to navigate (project methods only)
- Sink catalog: built-in list or project custom JSON file (see `apps/intellij-plugin/docs/sink-catalog.example.json`)
- Report (token paste + manual severity): select rows → **Report selected** → choose severity and upload (can include snippets)

## Features (planned)

0. **Call graph builder**: Build a project call graph from UAST, optionally persisted to disk and reloaded later.
1. **Audit memo**: Right-click PSI/UAST nodes (methods/fields/vars) to pin them into a memo list; click to navigate back.
2. **Sink search**: Initialize sinks from a configurable list, highlight matches in editors, and show results in a bottom panel.
3. **Call-chain discovery**: For each sink, reverse-search call chains using the call graph (with depth/path limits).
4. **Entry-point detection**: Detect common Java/Kotlin entry points (Spring MVC, JAX-RS, Micronaut, Quarkus Routes, Servlet, WebSocket, gRPC, Dubbo, `main`) and optionally filter call chains to those reaching them.
5. **Secrux integration**: Report call chains to Secrux using SARIF 2.1.0 (`codeFlows`) so the platform can reuse existing finding/AI review components.
6. **More**: Extensible actions (e.g., export, compare baselines, quick triage states).

## Docs

- `apps/intellij-plugin/docs/README.md`
- `apps/intellij-plugin/docs/ARCHITECTURE.md`
- `apps/intellij-plugin/docs/TECH_SELECTION.md`
- `apps/intellij-plugin/docs/SECRUX_INTEGRATION.md`
- `apps/intellij-plugin/docs/ROADMAP.md`

## Target IDE

- IntelliJ IDEA **2025.2+** (Community supported).
