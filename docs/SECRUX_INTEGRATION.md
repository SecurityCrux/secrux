# Secrux Integration

Status: **Draft**

This document describes how the IntelliJ plugin integrates with the Secrux platform.

## Objectives

- Report IDE-discovered call chains back to Secrux as **first-class findings**.
- Reuse existing Secrux UI and AI pipeline (snippet + dataflow + review history).
- Keep sensitive code safe: allow “coordinates-only” mode to avoid uploading snippets.

## Authentication

Recommended (future-proof):

- **OIDC Authorization Code + PKCE** against the same Keycloak realm used by Secrux Console.
- The plugin opens the system browser, receives a loopback redirect, then stores:
  - refresh token (PasswordSafe)
  - access token (in memory, auto-refreshed)

Fallback (v1-friendly):

- Manually paste an access token into plugin settings (stored in PasswordSafe).

## Minimal API Surface (current server)

The current Secrux server already provides:

- Create/select task:
  - `GET /tasks`
  - `POST /tasks`
- Ingest normalized findings:
  - `POST /tasks/{taskId}/findings` (`FindingBatchRequest`)
- Trigger AI review:
  - `POST /findings/{findingId}/ai-review`

## Recommended Server Addition (to truly “upload SARIF”)

To reuse the server’s SARIF parsing pipeline (`SarifFindingParser`) without duplicating logic in the plugin, add a new endpoint:

- `POST /tasks/{taskId}/findings:sarif`
  - Body: SARIF 2.1.0 JSON
  - Server: parse → normalize → upsert → emit “FindingsReady”

Notes:

- Keep the existing `POST /tasks/{taskId}/findings` as a low-level “normalized ingestion” API.
- The new endpoint is “SARIF ingestion” for tools like the IDE plugin.

## SARIF Mapping (plugin → Secrux)

Secrux extracts dataflow from SARIF `codeFlows` (see server `SarifFindingParser.extractDataflowFromSarif`).

### Per finding

- One SARIF `result` per sink match / call chain.
- `result.ruleId`: sink id (e.g. `java-runtime-exec`)
- `result.message.text`: human summary
- `result.locations[0]`: sink call site
- `result.codeFlows[0].threadFlows[0].locations[]`: ordered chain steps

### Location message conventions

To enrich UI roles, the plugin can encode step roles via message prefixes:

- `source: ...`
- `propagator: ...`
- `sink: ...`

This matches the server’s role inference logic.

### Snippets

Two modes:

1. **Embed snippets** in SARIF:
   - `physicalLocation.region.snippet.text`
2. **Coordinates only**:
   - Provide only `uri + startLine/startColumn/endColumn`
   - Secrux can resolve snippets from git/archives where available.

## Fingerprint Strategy

Secrux requires a stable `fingerprint` per finding.

Recommendation:

- `fingerprint = sha256(tenant + project + commit + ruleId + sinkLocation + chainSignature)`
- Base64url or hex-encoded.

## Reporting Workflow (suggested)

1. User selects a Secrux **project** and a target **task** (or creates a new “IDE Audit” task).
2. User selects a call chain in *Secrux Results*.
3. Plugin generates SARIF:
   - tool name: `secrux-intellij-plugin`
   - rule id: sink id
   - codeFlows: call chain steps
4. Plugin calls **SARIF ingestion** endpoint (recommended) or the normalized ingestion endpoint (fallback).
5. Secrux stores the finding and exposes it in Console.
6. Optional: plugin triggers AI review via `POST /findings/{findingId}/ai-review`.

## Security Considerations

- Store secrets only in IntelliJ `PasswordSafe`.
- Default to **coordinates-only** mode for sensitive repositories.
- Clearly display what data will be uploaded (paths, snippets, repo URL/commit).
- Support per-tenant base URLs and strict TLS validation.

