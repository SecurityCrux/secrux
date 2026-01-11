# Integration Guide

This document explains how to wire `secrux-ai` into the Secrux workflow engine. The AI service never reads the control-plane database; all communication relies on stage events (`StageCompleted` / `FindingsReady`) and HTTP callbacks.

## 1. Runtime Trigger

1. The Kotlin orchestrator emits a `StageCompleted` event (JSON as defined in the platform design doc).
2. Configure the workflow engine to run:

```bash
uv run python -m secrux_ai.scripts.stage_runner \
  --event /var/run/secrux/event.json \
  --config /etc/secrux-ai/config.yaml
```

3. The event file can be produced by the orchestrator or read directly from Kafka and written to disk.

## 2. Configuration Layout (`config.yaml`)

```yaml
mcpProfiles:
  - name: default
    type: http
    params:
      base_url: https://mcp.local
      api_key: ${MCP_TOKEN}

callbacks:
  mode: webhook
  endpoint: https://secrux-api/tasks/stage-recommendations
  headers:
    X-Tenant-Id: tenant-123

agents:
  - name: signals
    kind: signal
  - name: heuristics
    kind: log
  - name: ai-review
    kind: mcp-review
    mcpProfile: default
    params:
      tool: stage-reviewer
```

- Additional agents/MCP implementations can be declared via `entrypoint: "module:Class"` without editing repository code.
- To keep configuration outside of files, implement a provider (e.g., `my_org.config:DbProvider`) and start the runner with `--config-provider-entrypoint`.

## 3. Callback Contract

The AI service posts results to the configured sink. The webhook payload mirrors `AgentRecommendation`:

```json
{
  "taskId": "1111-2222-3333",
  "stageId": "abcd",
  "stageType": "SCAN_EXEC",
  "generatedAt": "2025-01-01T00:00:00Z",
  "elapsedMs": 128,
  "findings": [
    {
      "agent": "ai-review",
      "severity": "HIGH",
      "status": "OPEN",
      "summary": "Potential injection in module foo",
      "details": {
        "ruleId": "java.sql.injection",
        "evidence": "src/foo/Foo.java:42"
      }
    }
  ],
  "metadata": {
    "agentCount": 3
  }
}
```

Secrux backend records the response as another stage artifact/log entry without direct DB coupling.

## 4. Custom Agents & MCP Clients

- Agents: create a Python class inheriting `BaseAgent` and expose it in a module. Reference it in config:

```yaml
agents:
  - name: sbom-review
    entrypoint: "acme.agents:SBOMGuard"
    params:
      policy: strict
```

- MCP clients: register new profiles with `entrypoint` and `params`. Agents select profiles via `mcpProfile`.

## 5. Observability

- The CLI prints JSON to stdout when `mode=stdout`; log drains (Loki, files) can capture this stream.
- HTTP errors (callbacks or MCP) raise exceptions and exit non-zero so that the orchestrator can retry.
- Future enhancements include Prometheus metrics and tracing hooks (OTel).

