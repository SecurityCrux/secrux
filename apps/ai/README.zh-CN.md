# Secrux AI Service（AI 微服务）

[English](README.md)

`secrux-ai` 是 Secrux 的 FastAPI 微服务，用于 AI Job、MCP/Agent 工作流，以及（可选的）扫描结果审阅/增强能力。

## 开发环境要求

- Python 3.11+
- 推荐使用 `uv`（可选，见 `pyproject.toml` 中的 `[tool.uv]`）

## 本地开发

```bash
cd apps/ai
python -m venv .venv
. .venv/bin/activate
pip install -e ".[dev]"

uvicorn service.main:app --host 0.0.0.0 --port 5156 --reload
```

健康检查：`http://localhost:5156/health`

## 单模块部署（Docker Compose）

该方式会在一个 compose 项目中启动：AI 服务 + 其 Postgres。

```bash
cd apps/ai
cp .env.example .env
docker compose up -d
docker compose ps
```

## 配置说明

复制 `.env.example` 为 `.env` 后按需调整。常用配置项：

- `SECRUX_AI_SERVICE_TOKEN`（需与后端配置一致）
- `AI_DATABASE_URL`（Postgres 连接串）
- 可选 LLM：`SECRUX_AI_LLM_BASE_URL`、`SECRUX_AI_LLM_API_KEY`、`SECRUX_AI_LLM_MODEL`
- 可选 prompt dump：`SECRUX_AI_PROMPT_DUMP`、`SECRUX_AI_PROMPT_DUMP_DIR`

## 配置项解析

### 端口（compose）

- `AI_PORT`：宿主机端口映射到 AI 服务容器的 `:5156`。

### 服务间鉴权

- `SECRUX_AI_SERVICE_TOKEN`：`secrux-server` 调用 AI 服务时使用的共享 token（视为密码）。

### 数据库（compose）

- `AI_POSTGRES_DB`、`AI_POSTGRES_USER`、`AI_POSTGRES_PASSWORD`：用于 `ai-postgres` 容器初始化。
- `AI_DATABASE_URL`：SQLModel/psycopg 连接串（示例：`postgresql+psycopg://user:pass@host:5432/db`）。

### LLM（可选）

- `SECRUX_AI_LLM_BASE_URL`、`SECRUX_AI_LLM_API_KEY`、`SECRUX_AI_LLM_MODEL`：配置上游 LLM；留空则禁用在线调用。

### Prompt dump（可选，调试用）

- `SECRUX_AI_PROMPT_DUMP`：`off` | `file` | `stdout`。
- `SECRUX_AI_PROMPT_DUMP_DIR`：当使用 `file` 时的输出目录。
- 其他过滤项与参数见 `apps/ai/.env.example`。
