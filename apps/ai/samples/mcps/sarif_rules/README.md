# SARIF Rule Lookup MCP

> 提示：AI Service 内置了 `/builtin/sarif-rules`，无需上传此包；下述内容用于本地调试或扩展。

This MCP exposes a single tool `sarif.rule` that loads a SARIF 2.1.0 report and
returns metadata for a given rule ID (name, descriptions, help URI, etc.).

## Environment variables

| Variable | Description | Default |
| --- | --- | --- |
| `SARIF_RULE_ROOT` | Base directory containing SARIF files. Requests cannot escape this root. | current working directory |
| `SARIF_RULE_ALLOWED_EXTS` | Allowed extensions (comma-separated). | `.sarif,.json` |
| `SARIF_RULE_MAX_RULES` | Maximum rules indexed per file to avoid memory spikes. | `1000` |
| `SARIF_RULE_MAX_FILE_MB` | Maximum SARIF file size accepted. | `25` |

## Local run

```bash
cd apps/ai/samples/mcps/sarif_rules
python -m venv .venv
source .venv/bin/activate
pip install -e .

export SARIF_RULE_ROOT=/path/to/reports
uvicorn sarif_rules.server:app --host 0.0.0.0 --port 7080
```

Query example:

```bash
curl -X POST http://localhost:7080/tools/sarif.rule:invoke \
  -H "Content-Type: application/json" \
  -d '{"path":"scan-results/app.sarif","ruleId":"JS-001"}'
```

## Uploading to Secrux

1. `zip -r ../sarif-rules-mcp.zip sarif_rules README.md pyproject.toml`
2. In **AI Clients → MCP Profiles**, upload the ZIP, entrypoint `sarif_rules.server:app`.
3. Params example:
   ```json
   {
     "command": "uvicorn sarif_rules.server:app --host 0.0.0.0 --port 7080",
     "port": 7080,
     "env": {
       "SARIF_RULE_ROOT": "/workspace/artifacts/sarif",
       "SARIF_RULE_MAX_FILE_MB": "25"
     }
   }
   ```
4. Agents that need rule metadata can now call `sarif.rule` via `McpReviewAgent`.
