# Secrux Console

[中文说明](README.zh-CN.md)

`secrux-console` is the Vite + React web UI for the Secrux platform.

## Development requirements

- Node.js 20+
- npm (or compatible)

## Local development

```bash
cd secrux-console
npm install
npm run dev
```

Configure API / OIDC via Vite env (see `env.example`), for example:

- `VITE_API_BASE_URL=http://localhost:8080`
- `VITE_OIDC_BASE_URL=http://localhost:8081`

## Deployment (Nginx container)

Production deploy uses the provided `Dockerfile` to build the static site and serve it with an **independent Nginx container**.

Runtime config is injected at container startup by writing `/env-config.js` and exposing it to the app
(`index.html` loads it before the main bundle). Configure with environment variables:

- `SECRUX_API_BASE_URL`
- `SECRUX_AUTH_MODE_UI` (or `SECRUX_AUTH_MODE`)
- `SECRUX_OIDC_BASE_URL`, `SECRUX_OIDC_REALM`, `SECRUX_OIDC_CLIENT_ID`, `SECRUX_OIDC_SCOPE`
- `SECRUX_APP_VERSION`

### Standalone deploy (Docker Compose)

```bash
cd secrux-console
cp .env.example .env
docker compose up -d
docker compose ps
```

Default URL: `http://localhost:5173`

## Configuration reference

### Local dev (Vite env)

These are compile-time variables used by `npm run dev` / `npm run build` (see `env.example`):

- `VITE_API_BASE_URL`: Secrux Server API base URL (browser-facing).
- `VITE_AUTH_MODE`: UI auth mode (`keycloak` or `local`).
- `VITE_OIDC_BASE_URL`, `VITE_OIDC_REALM`, `VITE_OIDC_CLIENT_ID`, `VITE_OIDC_SCOPE`: OIDC settings for Keycloak.
- `VITE_APP_VERSION`: Optional version string shown in the UI.

### Container runtime (Nginx)

These are injected into `/env-config.js` at container start:

- `CONSOLE_PORT`: Host port mapped to the Nginx container `:80` (used by `docker-compose.yml`).
- `SECRUX_API_BASE_URL`: API base URL for users’ browsers.
- `SECRUX_AUTH_MODE_UI`: UI auth mode (`keycloak` or `local`). Fallback: `SECRUX_AUTH_MODE`.
- `SECRUX_OIDC_BASE_URL`, `SECRUX_OIDC_REALM`, `SECRUX_OIDC_CLIENT_ID`, `SECRUX_OIDC_SCOPE`: OIDC settings.
- `SECRUX_APP_VERSION`: Version string.
