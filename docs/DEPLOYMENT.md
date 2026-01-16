# Secrux Deployment Guide

[中文说明](DEPLOYMENT.zh-CN.md)

> This document focuses on getting Secrux running and usable. It covers recommended deployment for dev/test/prod, dependencies, port planning, verification, and troubleshooting.  
> If you only want to run backend dependencies locally, see `docs/STARTUP.md` (note: the Gradle Wrapper lives at `apps/server/gradlew`).

## 1. What you are deploying

Secrux is an auditable, extensible, multi-tenant security scanning platform. Its goal is to unify different scanning types (SAST/Secrets/IaC/SCA, etc.) into one consistent workflow:

- A **Task / Stage** lifecycle and orchestration model (source prepare → rules prepare → scan exec → result process → review…).
- A **unified result model** (platform Findings) and standard artifacts (e.g. SARIF, CycloneDX SBOM), with report export support.
- **Control-plane / execution-plane separation**: the control plane handles orchestration/auth/storage; the execution plane (executor agent) runs engine containers in an isolated environment and uploads logs/results.
- **AI assistance**: an independent `secrux-ai` service provides configurable agents/MCP tools/Knowledge Base (RAG) for automated review and suggestions.

Typical use cases:

- Internal security baselines and continuous audit for enterprise code/artifact repositories (multi-tenant / multi-project isolation).
- Offload scan workloads from API nodes to dedicated executor nodes.
- Introduce AI in the review stage (configurable, auditable), optionally backed by a tenant knowledge base to reduce hallucinations.

## 2. Architecture and modules

### 2.1 Component list (repo directories)

| Component | Directory | Purpose | Runtime |
|---|---|---|---|
| Control plane API | `apps/server/` | AuthN/AuthZ, task orchestration, results storage, REST API, Executor Gateway | Spring Boot (JDK 21) |
| Console UI | `apps/web/` | UI for tasks/logs/results/AI config | Vite + React (built static assets) |
| Executor agent | `apps/executor/` | Connects to Executor Gateway, launches engine containers, uploads logs/results | Go binary + Docker |
| Engines/scripts | `apps/engines/` | Engine images + run scripts (Semgrep/Trivy, etc.) | Docker images/scripts |
| AI microservice | `apps/ai/` | AI jobs, agents/MCP, Knowledge Base (RAG) | FastAPI + Postgres |
| Single-machine quickstart | `docker/docker-compose.yml` | Postgres/Kafka/Redis/Keycloak + `secrux-server` + console (service `secrux-console`) + `secrux-ai` | Docker Compose |

### 2.2 High-level data flow

```text
Console  --(REST/JWT)-->  secrux-server  --(SQL)--> Postgres
                                |           |
                                |           +--> (Kafka) stage/outbox/events
                                |
                                +--(x-platform-token)--> secrux-ai  --(SQL)--> ai-postgres
                                |
                                +--(TLS:5155) <--- secrux-executor (executor-agent)  --(Docker)--> engines
                                |
                                +--(HTTP:8080, X-Executor-Token) <--- secrux-executor (download/upload artifacts)
```

## 3. Ports, domains, and network requirements

### 3.1 Default ports (repo `docker/docker-compose.yml`)

| Service | Port | Notes |
|---|---:|---|
| `secrux-server` | 8080 | Control plane API (docs: `/doc.html`; actuator: `/actuator/*`) |
| `secrux-console` | 5173 | Web UI (served by an Nginx container) |
| Keycloak | 8081 | OIDC IdP (dev realm imported from `keycloak/realm-secrux.json`) |
| Executor Gateway | 5155 | Entry point for executor agents |
| AI service | 5156 | `secrux-ai` FastAPI (health: `/health`) |
| Postgres | 5432 | Main DB (default DB/user/pass: `secrux`) |
| Kafka (host) | 19092 | Host access port (avoid common 9092 conflicts) |
| Kafka (docker network) | 29092 | Container-to-container access (`kafka:29092`) |
| Zookeeper | 2181 | Kafka dependency |
| Redis | 6379 | Optional cache placeholder |

### 3.2 Network connectivity (production-critical)

- The Console must reach `secrux-server` and Keycloak.
- The executor host must reach:
  - Executor Gateway (`5155/TCP`, recommended internal-only).
  - `secrux-server` download/upload endpoints (default `http(s)://<api>/executor/uploads/*`).
  - Git repositories (clone), container registries (pull engine images), and any external dependency sources required by engines (e.g. Maven repos, Trivy DB sources).
- `secrux-server` must reach: Postgres, Kafka, Redis, Keycloak (and optional OPA).
- `secrux-ai` must reach: ai-postgres (and optional upstream LLM/RAG backend).

## 4. Local development deployment (recommended)

### 4.1 Prerequisites

- Docker & Docker Compose
- JDK 21
- Node.js 18+ (for `apps/web`)
- Go 1.22+ (for `apps/executor`)

### 4.2 Start infra dependencies (Postgres/Kafka/Redis/Keycloak/AI)

From repo root:

```bash
docker compose -f docker/docker-compose.yml up -d postgres redis zookeeper kafka keycloak
# Optional (start AI only when needed):
# docker compose -f docker/docker-compose.yml up -d ai-postgres ai-service
docker compose -f docker/docker-compose.yml ps
```

> For “one-command full-stack quickstart” (server + console + AI), see repo root `README.md`.

### 4.3 Start the backend API (Spring Boot)

In `apps/server/`:

```bash
cd apps/server
./gradlew flywayMigrate
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Verify:

- API: `http://localhost:8080`
- Knife4j: `http://localhost:8080/doc.html`
- Health: `http://localhost:8080/actuator/health`

### 4.4 Start the console (Vite dev server)

In `apps/web/`:

```bash
cd apps/web
npm install
npm run dev
```

Console config (defaults usually work; override via `apps/web/.env.local` if needed):

- `VITE_API_BASE_URL` (default `http://localhost:8080`)
- `VITE_OIDC_BASE_URL` (default `http://localhost:8081`)
- `VITE_OIDC_REALM` (default `secrux`)
- `VITE_OIDC_CLIENT_ID` (default `secrux-console`)

### 4.5 Getting a local Keycloak token (dev-only)

`docs/STARTUP.md` provides a copy-paste curl example. The dev realm includes a seeded `secrux/secrux` user and `secrux-api` client.

### 4.6 Register an executor and run the agent (`apps/executor`)

1) Register an executor (requires an admin Bearer token):

```bash
curl -X POST "http://localhost:8080/executors/register" \
  -H "Authorization: Bearer $KC_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "local-executor-1",
    "labels": {"zone":"local"},
    "cpuCapacity": 4,
    "memoryCapacityMb": 8192
  }'
```

The response includes `executorId`. Then fetch its token:

```bash
curl "http://localhost:8080/executors/<executorId>/token" \
  -H "Authorization: Bearer $KC_TOKEN"
```

2) Prepare executor config: copy `apps/executor/config.temp`, fill the token and gateway address (do not commit real tokens):

```bash
cp apps/executor/config.temp /tmp/secrux-agent.json
```

3) Start the agent (requires local Docker Engine with access to `/var/run/docker.sock`):

```bash
cd apps/executor
go build -o executor-agent .
./executor-agent -config /tmp/secrux-agent.json
```

4) Engine images (optional)

- If you want local engine images (matching the example mappings), build them in `apps/engines/`:

```bash
cd apps/engines
./build-local-engines.sh
```

> In production, prefer a controlled registry (e.g. `ghcr.io/...` or private Harbor) and explicitly map images via `engineImages`.

## 5. Single-node / test deployment (no K8s)

The repo `docker/docker-compose.yml` provides a “single-machine quickstart” full stack (including UI/AI). In test environments you can also run only infra services (Postgres/Kafka/Keycloak, etc.) and deploy each module separately:

- `apps/server`: build a fat jar and run via systemd or containers.
- `apps/web`: build static assets (`npm run build`) and serve via Nginx/Caddy/object storage.
- `apps/executor`: install as a systemd service on executor hosts (requires Docker Engine on the host).
- `apps/ai`: run as containers per your internal standards.

### 5.1 Build `apps/server`

```bash
cd apps/server
./gradlew test
./gradlew bootJar
ls -la build/libs
```

Run (example):

```bash
java -jar build/libs/secrux-server-*.jar
```

### 5.2 Build `apps/web`

```bash
cd apps/web
npm ci
npm run build
```

Deploy `apps/web/dist/` as a static site; for production, use a reverse proxy to unify domain and HTTPS.

## 6. Production deployment recommendations

### 6.1 Must-do security items

- Set and protect `SECRUX_CRYPTO_SECRET` (see `com.secrux.config.SecruxCryptoProperties`). Changing it will prevent decryption of stored repo credentials/tokens.
- Treat `SECRUX_AI_SERVICE_TOKEN` as a service credential for `secrux-server -> secrux-ai`; store it in a secret manager and do not bake it into images/repos.
- Treat executor tokens (`/executors/*/token`) as passwords: distribute per host and rotate on leakage.
- Enable TLS at least for external API/Keycloak/Executor Gateway. The gateway should be internal-only and use a trusted CA (keep agent `insecure=false`).

### 6.2 Authentication and authorization

- Dev: `SPRING_PROFILES_ACTIVE=local` (real Keycloak tokens, `secrux.authz` off by default for iteration).
- Prod: `secrux.auth.mode=KEYCLOAK`, and optionally enable `secrux.authz.enabled=true` to integrate OPA PDP (`SECRUX_AUTHZ_OPA_URL` / `SECRUX_AUTHZ_POLICY_PATH`).

### 6.3 Persistence and backups

- At minimum, back up:
  - Control-plane Postgres (tasks/results/repos/executors/ticket configs, etc.).
  - AI service ai-postgres (knowledge entries, agent/MCP configs, ai jobs).
- Backup/restore must be coupled with `SECRUX_CRYPTO_SECRET`, or sensitive fields cannot be decrypted after restore.

### 6.4 Executors and capacity planning

- Executors should run on dedicated node pools sized for CPU/memory/network/disk. Use labels to describe capabilities (e.g. `arch=x86_64`, `zone=...`, `policy=...`).
- `secrux.executor.dispatch.api-base-url` (`SECRUX_EXECUTOR_API_BASE_URL`) must be reachable from executor hosts, otherwise they cannot download uploaded sources/SBOMs.

## 7. Post-deploy verification checklist

1. `docker compose -f docker/docker-compose.yml ps`: dependencies are healthy.
2. `GET http://<api>/actuator/health`: control plane is healthy.
3. `GET http://<ai>/health`: AI service is healthy.
4. Keycloak login and token issuance work (Console can redirect to login).
5. Call `POST /executors/register` and start executor agent; confirm status transitions from `REGISTERED/OFFLINE` to `READY`.
6. Create a project/repo/task in the Console, observe stage logs and persisted results.

## 8. Troubleshooting

- **`./gradlew` not found**: run it from `apps/server/` (wrapper is at `apps/server/gradlew`).
- **executor agent cannot connect to 5155**: ensure `executor.gateway.enabled=true` (enabled in local profile) and check firewall/certificates.
- **executor agent cannot download uploads**: verify `SECRUX_EXECUTOR_API_BASE_URL` is reachable from executors and `X-Executor-Token` matches the issued token.
- **repo clone needs credentials**: fill BASIC/TOKEN auth in the “Repository” dialog; the platform encrypts and stores it and provides it to executors in task payloads.
