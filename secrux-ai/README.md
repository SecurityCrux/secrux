# Secrux AI Service

[中文说明](README.zh-CN.md)

`secrux-ai` is the FastAPI microservice used by Secrux for AI jobs, MCP/agent workflows, and (optional) review/enrichment features.

## Development requirements

- Python 3.11+
- Recommended: `uv` (optional, see `[tool.uv]` in `pyproject.toml`)

## Local development

```bash
cd secrux-ai
python -m venv .venv
. .venv/bin/activate
pip install -e ".[dev]"

uvicorn service.main:app --host 0.0.0.0 --port 5156 --reload
```

Health: `http://localhost:5156/health`

## Standalone deploy (Docker Compose)

This starts the AI service + its Postgres in one compose project.

```bash
cd secrux-ai
cp .env.example .env
docker compose up -d
docker compose ps
```

## Configuration

Copy `.env.example` to `.env` and adjust as needed. Common variables:

- `SECRUX_AI_SERVICE_TOKEN` (must match the server-side token)
- `AI_DATABASE_URL` (Postgres connection string)
- Optional LLM: `SECRUX_AI_LLM_BASE_URL`, `SECRUX_AI_LLM_API_KEY`, `SECRUX_AI_LLM_MODEL`
- Optional prompt dump: `SECRUX_AI_PROMPT_DUMP`, `SECRUX_AI_PROMPT_DUMP_DIR`

## Configuration reference

### Ports (compose)

- `AI_PORT`: Host port mapped to the AI service container `:5156`.

### Service-to-service auth

- `SECRUX_AI_SERVICE_TOKEN`: Shared token used by `secrux-server` to call the AI service (treat as a password).

### Database (compose)

- `AI_POSTGRES_DB`, `AI_POSTGRES_USER`, `AI_POSTGRES_PASSWORD`: Used by the `ai-postgres` container.
- `AI_DATABASE_URL`: SQLModel/psycopg connection string (example: `postgresql+psycopg://user:pass@host:5432/db`).

### LLM (optional)

- `SECRUX_AI_LLM_BASE_URL`, `SECRUX_AI_LLM_API_KEY`, `SECRUX_AI_LLM_MODEL`: Configure an upstream LLM provider; leave empty to disable live calls.

### Prompt dump (optional, debug)

- `SECRUX_AI_PROMPT_DUMP`: `off` | `file` | `stdout`.
- `SECRUX_AI_PROMPT_DUMP_DIR`: Directory for dump files when using `file`.
- Additional filters and knobs are documented in `secrux-ai/.env.example`.
