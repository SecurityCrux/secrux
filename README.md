<p align="center">
  <img src="./logo.svg" alt="Secrux" width="220" />
</p>

# Secrux

[中文说明](README.zh-CN.md)

Secrux is a multi-tenant, self-hostable security governance platform (single-machine quickstart supported).

Highlights:

- **Multi-tenant**: isolate data and access by tenant.
- **Open-source edition** ships with open-source code scanning + SCA engines (Semgrep/Trivy by default).
- **Pluggable engines**: add new scanners quickly by providing engine images/scripts.
- **Stage-based orchestration**: tasks are composed from stages, enabling multiple task modes.
- **AI can take over** the whole flow: each stage can be AI-driven.
- **Roadmap**: upcoming versions will add a first-party SAST engine and enhanced AI auditing.

Core modules:

- **Control plane** (`secrux-server`): AuthN/AuthZ, task orchestration, results storage, Executor Gateway.
- **Console** (`secrux-web`): Web UI (served by an Nginx container in deployment).
- **Executor** (`secrux-executor`): A Go **binary** that connects to the gateway, runs engine containers, and uploads logs/results.
- **Engines** (`secrux-engine`): Semgrep/Trivy engine images and run scripts.
- **AI service** (`secrux-ai`): FastAPI service for AI jobs, MCPs, agents, and knowledge base.

## Clone (with submodules)

This repo uses Git submodules for the core modules. Clone with:

```bash
git clone --recurse-submodules <YOUR_REPO_URL>
```

If you already cloned without submodules:

```bash
git submodule update --init --recursive
```

## Quickstart (single machine)

1. Copy env template:

```bash
cp .env.example .env
```

2. Start the full stack (infra + server + console + AI):

```bash
docker compose up -d
docker compose ps
```

2.1. (Optional, recommended for remote executors) Generate TLS certs for the Executor Gateway (so executors can run with `insecure=false`):

```bash
./gen-executor-gateway-certs.sh
docker compose up -d --force-recreate secrux-server
```

3. Open:

- Console: `http://localhost:5173` (served via Nginx reverse proxy; browser calls use same-origin `/api/*` and `/auth/*` to avoid CORS)
- API: `http://localhost:8080` (Docs: `http://localhost:8080/doc.html`)
- Keycloak: `http://localhost:8081`
- AI service health: `http://localhost:5156/health`

Tip (server deploy): set `CONSOLE_PORT=80` in `.env` and open `http://<host>/`.

4. (Optional) Run an executor on the same machine:

```bash
cd secrux-executor
cp .env.example .env
go build -o executor-agent .
cp config.temp config.json
# edit config.json (server/token)
./executor-agent -config ./config.json
```

See `secrux-executor/README.md` for details (TLS, CA cert, token).

## Configuration

The quickstart compose reads environment variables from the repo-root `.env` (copy from `.env.example`).

- Console runtime (browser-facing): `SECRUX_API_BASE_URL`, `SECRUX_AUTH_MODE_UI`, `SECRUX_OIDC_BASE_URL`, `SECRUX_OIDC_REALM`, `SECRUX_OIDC_CLIENT_ID`, `SECRUX_OIDC_SCOPE`, `SECRUX_APP_VERSION`
- Server: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SECRUX_KAFKA_BOOTSTRAP_SERVERS`, `SECRUX_AUTH_MODE`, `SECRUX_AUTH_ISSUER_URI`, `SECRUX_AUTH_AUDIENCE`, `SECRUX_CRYPTO_SECRET`, `SECRUX_AI_DB_URL`, `SECRUX_AI_DB_USERNAME`, `SECRUX_AI_DB_PASSWORD`
- Keycloak admin (used by server for user/role management): `SECRUX_KEYCLOAK_ADMIN_BASE_URL`, `SECRUX_KEYCLOAK_ADMIN_REALM`, `SECRUX_KEYCLOAK_ADMIN_CLIENT_ID`, `SECRUX_KEYCLOAK_ADMIN_CLIENT_SECRET`
- AI integration: `SECRUX_AI_SERVICE_BASE_URL`, `SECRUX_AI_SERVICE_TOKEN`, `AI_DATABASE_URL`, `SECRUX_AI_LLM_BASE_URL`, `SECRUX_AI_LLM_API_KEY`, `SECRUX_AI_LLM_MODEL`
- Executor Gateway: `EXECUTOR_GATEWAY_ENABLED`, `EXECUTOR_GATEWAY_PORT`, `EXECUTOR_GATEWAY_CERTIFICATE_PATH`, `EXECUTOR_GATEWAY_PRIVATE_KEY_PATH`
- Optional (remote executors): set `SECRUX_EXECUTOR_API_BASE_URL` to the URL executors should use to reach the API (defaults to `http://localhost:8080`).

## Default dev credentials (Keycloak realm import)

- Realm: `secrux`
- Client: `secrux-api` (secret `secrux-api-secret`)
- User: `secrux` / `secrux`
- Tenant: `4223be89-773e-4321-9531-833fc1cb77af`

## Production / multi-node

1. Infra node (Postgres/Kafka/Redis/Keycloak/AI Postgres):

```bash
cd deploy/production/infra
cp .env.example .env
docker compose up -d
```

2. Control-plane node (server + console + AI):

```bash
cd deploy/production/control-plane
cp .env.example .env
docker compose up -d
```

3. Executor nodes (binary, no Docker container for the agent): `secrux-executor/README.md`

## More docs

- Docs index: `docs/README.md`
- Local dev (run backend on host): `docs/STARTUP.md`
- Deployment guide: `docs/DEPLOYMENT.md`
- Resource & configuration reference: `docs/RESOURCE_CONFIGURATION.md`
- Design docs (CN): `docs/design/secrux设计文档.md`

## License

Secrux is licensed under a modified Apache License 2.0 with additional conditions. See `LICENSE`.

Secrux name/logo usage: `TRADEMARKS.md`

## Contributing & Security

- Contributing: `CONTRIBUTING.md` (CN: `CONTRIBUTING.zh-CN.md`)
- Security policy: `SECURITY.md` (CN: `SECURITY.zh-CN.md`)

## Acknowledgements

Thanks to the SecurityCruxteam and the Java Chains team.

SecurityCrux team (key members):

<p>
  <a href="https://github.com/springkill"><img src="https://github.com/springkill.png?size=80" width="56" height="56" alt="springkill" /></a>
  <a href="https://github.com/4ra1n"><img src="https://github.com/4ra1n.png?size=80" width="56" height="56" alt="4ra1n" /></a>
  <a href="https://github.com/Ar3h"><img src="https://github.com/Ar3h.png?size=80" width="56" height="56" alt="Ar3h" /></a>
  <a href="https://github.com/CHYbeta"><img src="https://github.com/CHYbeta.png?size=80" width="56" height="56" alt="CHYbeta" /></a>
  <a href="https://github.com/phith0n"><img src="https://github.com/phith0n.png?size=80" width="56" height="56" alt="phith0n" /></a>
  <a href="https://github.com/ReaJason"><img src="https://github.com/ReaJason.png?size=80" width="56" height="56" alt="ReaJason" /></a>
  <a href="https://github.com/ssrsec"><img src="https://github.com/ssrsec.png?size=80" width="56" height="56" alt="ssrsec" /></a>
  <a href="https://github.com/su18"><img src="https://github.com/su18.png?size=80" width="56" height="56" alt="su18" /></a>
  <a href="https://github.com/unam4"><img src="https://github.com/unam4.png?size=80" width="56" height="56" alt="unam4" /></a>
  <a href="https://github.com/xcxmiku"><img src="https://github.com/xcxmiku.png?size=80" width="56" height="56" alt="xcxmiku" /></a>
  <a href="https://github.com/novemberrainz0908"><img src="https://github.com/novemberrainz0908.png?size=80" width="56" height="56" alt="novemberrainz0908" /></a>
  <a href="https://github.com/Kalix-lee"><img src="https://github.com/Kalix-lee.png?size=80" width="56" height="56" alt="Kalix-lee" /></a>
  <a href="https://github.com/acety1ene"><img src="https://github.com/acety1ene.png?size=80" width="56" height="56" alt="acety1ene" /></a>
</p>

Java Chains team (key members):

<p>
  <a href="https://github.com/4ra1n"><img src="https://github.com/4ra1n.png?size=80" width="56" height="56" alt="4ra1n" /></a>
  <a href="https://github.com/Ar3h"><img src="https://github.com/Ar3h.png?size=80" width="56" height="56" alt="Ar3h" /></a>
  <a href="https://github.com/CHYbeta"><img src="https://github.com/CHYbeta.png?size=80" width="56" height="56" alt="CHYbeta" /></a>
  <a href="https://github.com/phith0n"><img src="https://github.com/phith0n.png?size=80" width="56" height="56" alt="phith0n" /></a>
  <a href="https://github.com/ReaJason"><img src="https://github.com/ReaJason.png?size=80" width="56" height="56" alt="ReaJason" /></a>
  <a href="https://github.com/springkill"><img src="https://github.com/springkill.png?size=80" width="56" height="56" alt="springkill" /></a>
  <a href="https://github.com/ssrsec"><img src="https://github.com/ssrsec.png?size=80" width="56" height="56" alt="ssrsec" /></a>
  <a href="https://github.com/su18"><img src="https://github.com/su18.png?size=80" width="56" height="56" alt="su18" /></a>
  <a href="https://github.com/unam4"><img src="https://github.com/unam4.png?size=80" width="56" height="56" alt="unam4" /></a>
  <a href="https://github.com/xcxmiku"><img src="https://github.com/xcxmiku.png?size=80" width="56" height="56" alt="xcxmiku" /></a>
</p>

## Disclaimer

See `DISCLAIMER.md` (CN: `DISCLAIMER.zh-CN.md`).
