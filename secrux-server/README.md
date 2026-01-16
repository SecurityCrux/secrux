# Secrux Server

[中文说明](README.zh-CN.md)

`secrux-server` is the Spring Boot control-plane API for Secrux. It provides multi-tenant auth, task orchestration, data storage, and the Executor Gateway used by `executor-agent`.

## Development requirements

- JDK 21
- Docker (recommended for Postgres/Kafka/Keycloak)

## Local development (run on host)

1. Start infra (Postgres/Redis/Kafka/Keycloak) from the repo root:

```bash
docker compose up -d postgres redis zookeeper kafka keycloak
```

2. Run the server:

```bash
cd secrux-server
export SPRING_PROFILES_ACTIVE=local
./gradlew bootRun
```

3. Run tests:

```bash
./gradlew test
```

## Standalone deploy (Docker Compose)

This starts the server + Postgres + Kafka + Keycloak in one compose project.

```bash
cd secrux-server
cp .env.example .env
docker compose up -d
docker compose ps
```

Default ports:
- API: `http://localhost:8080` (Docs: `http://localhost:8080/doc.html`)
- Keycloak: `http://localhost:8081`
- Kafka (host): `127.0.0.1:19092`
- Postgres: `localhost:5432`

## Configuration

Copy `.env.example` to `.env` and adjust as needed. Common variables:

- Auth: `SECRUX_AUTH_MODE`, `SECRUX_AUTH_ISSUER_URI`, `SECRUX_AUTH_AUDIENCE`
- Keycloak admin (user/role management): `SECRUX_KEYCLOAK_ADMIN_BASE_URL`, `SECRUX_KEYCLOAK_ADMIN_CLIENT_SECRET`
- Crypto: `SECRUX_CRYPTO_SECRET` (required for production)
- Kafka: `SECRUX_KAFKA_BOOTSTRAP_SERVERS`
- AI integration (optional): `SECRUX_AI_SERVICE_BASE_URL`, `SECRUX_AI_SERVICE_TOKEN`
- Executor Gateway: `EXECUTOR_GATEWAY_ENABLED`, `EXECUTOR_GATEWAY_PORT`

## Configuration reference

### Ports (compose)

- `SECRUX_SERVER_PORT`: Host port mapped to the API container `:8080`.
- `EXECUTOR_GATEWAY_PORT`: Host port mapped to the gateway listener (container `:5155`).
- `KEYCLOAK_PORT`, `POSTGRES_PORT`, `KAFKA_HOST_PORT`: Host ports for infra containers in `secrux-server/docker-compose.yml`.

### Database

- `SPRING_DATASOURCE_URL`: JDBC URL (in compose usually `jdbc:postgresql://postgres:5432/secrux`).
- `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`: DB credentials.

### Kafka

- `SECRUX_KAFKA_BOOTSTRAP_SERVERS`: Kafka bootstrap servers (in compose usually `kafka:29092`).

### Authentication

- `SECRUX_AUTH_MODE`: `KEYCLOAK` or `LOCAL` (backend auth mode).
- `SECRUX_AUTH_ISSUER_URI`: OIDC issuer when `KEYCLOAK` mode (example: `http://keycloak:8081/realms/secrux`).
- `SECRUX_AUTH_AUDIENCE`: Required audience/client ID in access tokens (example: `secrux-api`).

### Keycloak admin (IAM management)

Used by the backend to manage users/roles when running in Keycloak mode.

- `SECRUX_KEYCLOAK_ADMIN_BASE_URL`: Keycloak base URL (example: `http://keycloak:8081`).
- `SECRUX_KEYCLOAK_ADMIN_REALM`: Realm name (default `secrux`).
- `SECRUX_KEYCLOAK_ADMIN_CLIENT_ID`: Admin client ID (default `secrux-admin`).
- `SECRUX_KEYCLOAK_ADMIN_CLIENT_SECRET`: Admin client secret (must match the realm import).

### Executor dispatch (API base URL for executors)

- `SECRUX_EXECUTOR_API_BASE_URL`: URL that executors should use to reach the API when downloading/uploading artifacts (defaults to `http://localhost:8080`).

### Crypto

- `SECRUX_CRYPTO_SECRET`: Encryption key used for stored credentials (must be stable in production).

### AI integration (optional)

- `SECRUX_AI_SERVICE_BASE_URL`: AI service base URL.
- `SECRUX_AI_SERVICE_TOKEN`: Service-to-service token shared with `secrux-ai`.

### Executor Gateway (TLS)

- `EXECUTOR_GATEWAY_ENABLED`: Enables the gateway listener.
- `EXECUTOR_GATEWAY_CERTIFICATE_PATH`, `EXECUTOR_GATEWAY_PRIVATE_KEY_PATH`: Optional PEM cert/key; if empty, the server generates a self-signed cert at startup.
