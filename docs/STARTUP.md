# Secrux Local Environment Boot Guide

[中文说明](STARTUP.zh-CN.md)

This guide explains how to spin up the supporting services (database, Kafka, Redis) and run the Secrux backend locally.

For full-stack deployment (console + executors + AI service) and configuration sizing, also see:

- `docs/DEPLOYMENT.md`
- `docs/RESOURCE_CONFIGURATION.md`

## 1. Prerequisites

- Docker & Docker Compose
- JDK 21 (ensure `java -version` shows 21)
- `apps/server/gradlew` executable (already generated in the repo)

## 2. Start Infra Dependencies

From the repository root:

```bash
docker compose -f docker/docker-compose.yml up -d postgres redis zookeeper kafka keycloak
```

Optional (AI service + its Postgres):

```bash
docker compose -f docker/docker-compose.yml up -d ai-postgres ai-service
```

For full-stack quickstart (server + console + AI), see `README.md`.

Services provisioned (infra):

| Service   | Port | Notes |
|-----------|------|-------|
| Postgres  | 5432 | DB `secrux`, user/password `secrux` |
| Zookeeper | 2181 | Required by Kafka |
| Kafka     | 19092 | PLAINTEXT listeners for localhost (avoids common local port conflicts on 9092) |
| Redis     | 6379 | Optional cache placeholder |
| Keycloak  | 8081 | OIDC IdP; dev realm auto-imported from `keycloak/realm-secrux.json` |
| AI service (optional) | 5156 | FastAPI service; health `/health` |
| AI Postgres (optional) | (internal) | Dedicated DB for AI service |

You can inspect container health:

```bash
docker compose -f docker/docker-compose.yml ps
```

## 3. Run Database Migrations

Once Postgres is healthy:

```bash
cd apps/server
./gradlew flywayMigrate
```

This seeds all tables defined under `src/main/resources/db/migration`.

## 4. Start the Spring Boot Service

```bash
./gradlew bootRun
```

The API will listen on `http://localhost:8080`. Knife4j UI: `http://localhost:8080/doc.html`.

To stop the app, press `Ctrl+C`.

## 5. Authentication Modes

- **Local development (dockerized Keycloak)**: run with `SPRING_PROFILES_ACTIVE=local` (or add `--spring.profiles.active=local` to `bootRun`).  
  This profile expects real OIDC tokens issued by the bundled Keycloak realm at `http://localhost:8081/realms/secrux`, while keeping `secrux.authz` disabled for faster iteration.
- **Legacy HMAC mode**: override `SECRUX_AUTH_MODE=LOCAL` if you still need to test with self-signed JWTs (no IdP required).
- **Keycloak + OPA (production)**: leave the default profile, then provide:
  - `SECRUX_AUTH_ISSUER_URI` → Keycloak realm issuer (e.g. `https://sso.company.com/realms/secrux`)
  - `SECRUX_AUTH_AUDIENCE` → API client ID expected in access tokens
  - `SECRUX_AUTHZ_ENABLED=true` plus `SECRUX_AUTHZ_OPA_URL` / `SECRUX_AUTHZ_POLICY_PATH` to point at the PDP service
  - `SECRUX_CRYPTO_SECRET` → encryption key for credential ciphers (required; rotate carefully)

When `secrux.authz` is enabled, every controller call emits `{subject, action, resource, context}` to OPA. For safety, Secrux always fails closed when OPA is unavailable.

## 6. Getting a Dev Token from Keycloak

The dev realm ships with a seeded tenant/user that matches the backend defaults:

| Item | Value |
|------|-------|
| Realm | `secrux` |
| Client ID | `secrux-api` |
| Client secret | `secrux-api-secret` |
| Username | `secrux` |
| Password | `secrux` |
| Tenant UUID | `4223be89-773e-4321-9531-833fc1cb77af` |

1. Ensure `docker compose -f docker/docker-compose.yml up -d keycloak` is running (it is already part of the blanket `docker compose -f docker/docker-compose.yml up -d` command).
2. Request a token via the Direct Access Grant flow:

```bash
export KC_TOKEN=$(curl -s -X POST http://localhost:8081/realms/secrux/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=secrux-api" \
  -d "client_secret=secrux-api-secret" \
  -d "grant_type=password" \
  -d "username=secrux" \
  -d "password=secrux" | jq -r .access_token)
```

3. Call the API with the bearer token (example lists projects):

```bash
curl -H "Authorization: Bearer $KC_TOKEN" http://localhost:8080/projects
```

You should receive an `ApiResponse` JSON payload. Treat the credentials as **dev-only** and rotate them if you share the environment.

## 7. Useful Environment Variables

You can override connection details if needed:

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/secrux` | Postgres JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `secrux` | DB user |
| `SPRING_DATASOURCE_PASSWORD` | `secrux` | DB password |
| `SECRUX_AUTH_ISSUER_URI` | `http://localhost:8081/realms/secrux` | Keycloak realm issuer |
| `SECRUX_AUTH_AUDIENCE` | `secrux-api` | Expected audience/client ID |
| `SECRUX_AUTHZ_ENABLED` | `false` | Enable OPA authorization |
| `SECRUX_AUTHZ_OPA_URL` | `http://localhost:8181` | OPA base URL |
| `SECRUX_AUTHZ_POLICY_PATH` | `/v1/data/secrux/allow` | Policy to call |
| `SECRUX_CRYPTO_SECRET` | *(required)* | Encryption key for stored credential ciphers |
| `SECRUX_AI_SERVICE_BASE_URL` | `http://localhost:5156` | AI service base URL (optional) |
| `SECRUX_AI_SERVICE_TOKEN` | `local-dev-token` | Service-to-service token for `secrux-server -> secrux-ai` |

## 8. Shutting Down

```bash
docker compose -f docker/docker-compose.yml down
```

If you need a clean reset (e.g., after squashing Flyway migrations), remove volumes too:

```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
```

Add `-v` if you want to remove Postgres data volume.
