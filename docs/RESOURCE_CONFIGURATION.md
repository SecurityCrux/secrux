# Secrux Resource & Configuration Guide

[中文说明](RESOURCE_CONFIGURATION.zh-CN.md)

> This document focuses on “what to configure for production readiness, how much resources each component needs, and how to scale/tune”.  
> For step-by-step deployment, see `docs/DEPLOYMENT.md`.

## 1. Configuration entry points

| Scope | Where to configure | Typical usage |
|---|---|---|
| Control plane (Spring Boot) | `apps/server/src/main/resources/application.yml` + environment variable overrides | DB/Kafka/Auth/AI/Executor/storage paths, etc. |
| Console (Vite) | `apps/web/.env.local` or inject `VITE_*` | API base URL, OIDC params |
| AI service (FastAPI) | repo root `.env` (compose) or injected environment variables | ai-service token, DB, LLM, RAG, debugging |
| Executor (Go) | `apps/executor/config.temp` (template) + `-config` | gateway, token, engine images, Trivy behavior |

## 2. Control-plane key configuration (`apps/server`)

### 2.1 Required (production must set)

| Spring property | Environment variable | Notes |
|---|---|---|
| `secrux.crypto.secret` | `SECRUX_CRYPTO_SECRET` | Encrypts stored repo credentials, Semgrep tokens, ticket system tokens, etc. Changing it breaks decryption of existing ciphers. |
| `spring.datasource.url` / `username` / `password` | `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | Control-plane main database |
| `spring.kafka.bootstrap-servers` | `SECRUX_KAFKA_BOOTSTRAP_SERVERS` | Kafka address (local dev default uses `127.0.0.1:19092`) |
| `secrux.auth.mode` | `SECRUX_AUTH_MODE` | `LOCAL` / `KEYCLOAK` (production typically `KEYCLOAK`) |
| `secrux.auth.issuer-uri` / `audience` | `SECRUX_AUTH_ISSUER_URI` / `SECRUX_AUTH_AUDIENCE` | Keycloak realm issuer and expected audience |
| `secrux.ai.service.base-url` / `token` | `SECRUX_AI_SERVICE_BASE_URL` / `SECRUX_AI_SERVICE_TOKEN` | Service credential for `secrux-server -> secrux-ai` (must match on both sides) |
| `secrux.executor.dispatch.api-base-url` | `SECRUX_EXECUTOR_API_BASE_URL` | API base URL used by executors for download/upload endpoints (must be reachable from executor hosts) |

Note: Spring Boot supports relaxed binding for env vars (`.` / `-` / `_` are interchangeable; case-insensitive).

### 2.2 Common optional settings

| Property | Environment variable | Notes |
|---|---|---|
| `secrux.authz.enabled` | `SECRUX_AUTHZ_ENABLED` | Enable OPA authorization (ABAC/RBAC), default `false` |
| `secrux.authz.opa-url` / `policy-path` | `SECRUX_AUTHZ_OPA_URL` / `SECRUX_AUTHZ_POLICY_PATH` | OPA PDP URL and policy path |
| `secrux.upload.root` | `SECRUX_UPLOAD_ROOT` | Upload storage path (use a volume/shared storage in HA setups) |
| `secrux.workspace.root` | `SECRUX_WORKSPACE_ROOT` | Workspace root (mainly for local scans/debugging) |
| `secrux.keycloak.admin.*` | `SECRUX_KEYCLOAK_ADMIN_*` | Control plane access to Keycloak Admin API (user management, etc.) |

### 2.3 AI config database (`spring.datasource.ai` / `spring.flyway.ai`)

The control plane maintains an **AI configuration database** to store tenant-scoped AI client configs (e.g. `baseUrl/model/apiKey`) and delivers them to `secrux-ai` when triggering AI review jobs.

This DB is configured via `spring.datasource.ai.*` and defaults to the same Postgres as the control plane to reduce component count. You can also split it into a separate DB if required.

| Spring property | Environment variable | Default (see `application.yml`) | Notes |
|---|---|---|---|
| `spring.datasource.ai.url` | `SECRUX_AI_DB_URL` | `jdbc:postgresql://localhost:5432/secrux` | JDBC URL for AI config DB |
| `spring.datasource.ai.username` | `SECRUX_AI_DB_USERNAME` | `secrux` | Username |
| `spring.datasource.ai.password` | `SECRUX_AI_DB_PASSWORD` | `secrux` | Password |
| `spring.flyway.ai.*` | `SPRING_FLYWAY_AI_*` | preconfigured | Flyway settings for the AI config DB |

Important: `SECRUX_AI_DB_*` is **not** the same as `secrux-ai`’s `AI_DATABASE_URL`. The AI service has its own Postgres (`ai-postgres`).

### 2.4 Executor Gateway (built into the control plane)

The local profile enables the gateway via `executor.gateway.*` (see `apps/server/src/main/resources/application-local.yml`).

| Property | Environment variable | Notes |
|---|---|---|
| `executor.gateway.enabled` | `EXECUTOR_GATEWAY_ENABLED` | Enable/disable the gateway (default `false`) |
| `executor.gateway.port` | `EXECUTOR_GATEWAY_PORT` | Default `5155` |
| `executor.gateway.certificate-path` / `private-key-path` | `EXECUTOR_GATEWAY_CERTIFICATE_PATH` / `EXECUTOR_GATEWAY_PRIVATE_KEY_PATH` | PEM certificate/key; when missing, server may generate a self-signed cert (dev only) |
| `executor.gateway.max-frame-bytes` | `EXECUTOR_GATEWAY_MAX_FRAME_BYTES` | Max frame size (default 5 MiB) |

## 3. AI service configuration (`apps/ai` / `ai-service`)

| Environment variable | Default/example | Notes |
|---|---|---|
| `SECRUX_AI_SERVICE_TOKEN` | `local-dev-token` | Must match `secrux.ai.service.token` on the control plane |
| `AI_DATABASE_URL` | `postgresql+psycopg://...` | AI service Postgres (separate from control plane) |
| `SECRUX_AI_LLM_BASE_URL` / `SECRUX_AI_LLM_API_KEY` / `SECRUX_AI_LLM_MODEL` | empty | Global LLM config (empty disables live LLM calls) |
| `AI_RAG_PROVIDER` | `local` / `ragflow` | Knowledge Base backend (see `docs/ragflow-switch-guide.md`) |
| `SECRUX_AI_PROMPT_DUMP*` | see repo `.env` | Prompt dump for debugging (disable or strictly control in production) |

## 4. Console configuration (`apps/web`)

| Env var | Default (code fallback) | Notes |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Control plane API |
| `VITE_OIDC_BASE_URL` | `http://localhost:8081` | Keycloak base URL |
| `VITE_OIDC_REALM` | `secrux` | Realm |
| `VITE_OIDC_CLIENT_ID` | `secrux-console` | OIDC clientId |
| `VITE_OIDC_SCOPE` | `openid` | scope |
| `VITE_APP_VERSION` | `dev` | UI version display/telemetry |

## 5. Executor configuration (`apps/executor`)

Start from `apps/executor/config.temp` (the template contains field comments). Core fields:

| Field | Notes |
|---|---|
| `server` | Executor Gateway address (e.g. `gateway.secrux.internal:5155`) |
| `serverName` | TLS server name override (optional; defaults to `server` host) |
| `caCertPath` | CA/self-signed PEM path (optional; used when `insecure=false`) |
| `token` | Issued executor token (treat as a password) |
| `insecure` | Skip TLS verification (dev only; must be `false` in production) |
| `engineImages` | Engine name → image mapping (`semgrep`, `trivy`, …) |
| `trivy.*` | Trivy options and cache mount strategy (reduce network dependence, avoid dead Maven repos) |

## 6. Resource planning (CPU/memory/disk)

The numbers below are **starting points**, not limits. Actual usage depends heavily on concurrency, repo size, engine type (Trivy/Semgrep), and retention policies.

### 6.1 Minimum viable (PoC / single-node)

| Component | CPU | Memory | Disk/IO |
|---|---:|---:|---|
| `secrux-server` | 2 vCPU | 2–4 GiB | Logs + upload dir (SSD recommended) |
| Postgres | 2 vCPU | 4–8 GiB | 50+ GiB (grows with results) |
| Kafka + ZK | 2 vCPU | 4–6 GiB | 20+ GiB (depends on retention) |
| Keycloak | 1 vCPU | 1–2 GiB | 1+ GiB |
| Redis | 0.5–1 vCPU | 0.5–1 GiB | negligible |
| `secrux-ai` | 1 vCPU | 1–2 GiB | 1+ GiB |
| ai-postgres | 1 vCPU | 1–2 GiB | 10+ GiB (knowledge base/jobs) |
| each executor | 4+ vCPU | 8+ GiB | Docker image cache + engine cache (SSD strongly recommended) |

### 6.2 Small team (recommended baseline)

- Control plane API: 2–4 vCPU / 4–8 GiB (scale horizontally; share DB/Kafka)
- Postgres: 4 vCPU / 16 GiB (dedicated SSD, backup & monitoring)
- Kafka: 4 vCPU / 16 GiB (or managed Kafka)
- Executors: size by “concurrency × per-task limits”; use dedicated pools

## 7. Tuning tips (common bottlenecks)

### 7.1 Database (Postgres)

- Monitor connections and slow queries; tune Hikari `maximumPoolSize` if needed.
- Findings/logs grow quickly: plan retention/archival policies.
- Backup/restore drills: validate that `SECRUX_CRYPTO_SECRET` decrypts historical data after restore.

### 7.2 Kafka

- When using event-driven orchestration/consumers: tune partitions and retention to avoid disk exhaustion.
- Local compose uses single-replica defaults; production should use appropriate replication.

### 7.3 Executors and engine containers

- Trivy/Semgrep may trigger heavy outbound network traffic. Recommend:
  - Pre-pull images and persist Trivy DB/cache.
  - Mount Maven cache/settings (see Trivy options in `apps/executor`).
  - Enforce CPU/memory limits for engine containers (`cpuLimit`/`memoryLimitMb` from task payload).

### 7.4 AI service

- With LLM enabled, upstream latency impacts review completion; prefer async jobs and set timeouts/concurrency.
- If switching Knowledge Base to a vector backend (e.g. RAGFlow), include provider capacity and tenant isolation in planning.

## 8. Production checklist

- [ ] `SECRUX_CRYPTO_SECRET` set and backed up (do not rotate casually)
- [ ] `SECRUX_AI_SERVICE_TOKEN` stored in a secret manager and matches ai-service
- [ ] executor tokens distributed per host with least privilege; rotation supported
- [ ] TLS enabled for external API/Keycloak/Gateway
- [ ] Postgres and ai-postgres backups + monitoring in place
- [ ] executor nodes have sufficient disk cache and controlled network egress for dependencies/registries
