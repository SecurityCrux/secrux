# Secrux Console（前端）

[English](README.md)

`secrux-console` 是 Secrux 的 Vite + React Web 控制台。

## 开发环境要求

- Node.js 20+
- npm（或兼容包管理器）

## 本地开发

```bash
cd secrux-console
npm install
npm run dev
```

通过 Vite 环境变量配置后端/API 与 OIDC（见 `env.example`），例如：

- `VITE_API_BASE_URL=http://localhost:8080`
- `VITE_OIDC_BASE_URL=http://localhost:8081`

## 部署（独立 Nginx 容器）

生产部署使用本目录下的 `Dockerfile` 构建静态站点，并由**独立的 Nginx 容器**提供访问。

运行时配置通过容器启动时写入 `/env-config.js` 注入（`index.html` 会在主 bundle 之前加载它）。
可用环境变量：

- `SECRUX_API_BASE_URL`
- `SECRUX_AUTH_MODE_UI`（或 `SECRUX_AUTH_MODE`）
- `SECRUX_OIDC_BASE_URL`、`SECRUX_OIDC_REALM`、`SECRUX_OIDC_CLIENT_ID`、`SECRUX_OIDC_SCOPE`
- `SECRUX_APP_VERSION`

### 单模块部署（Docker Compose）

```bash
cd secrux-console
cp .env.example .env
docker compose up -d
docker compose ps
```

默认访问地址：`http://localhost:5173`

## 配置项解析

### 本地开发（Vite 环境变量）

这些是编译期变量（用于 `npm run dev` / `npm run build`，见 `env.example`）：

- `VITE_API_BASE_URL`：Secrux 后端 API 地址（浏览器访问的地址）。
- `VITE_AUTH_MODE`：控制台认证模式（`keycloak` 或 `local`）。
- `VITE_OIDC_BASE_URL`、`VITE_OIDC_REALM`、`VITE_OIDC_CLIENT_ID`、`VITE_OIDC_SCOPE`：Keycloak 的 OIDC 配置。
- `VITE_APP_VERSION`：可选的版本标识（用于页面展示）。

### 容器运行时（Nginx）

这些会在容器启动时写入 `/env-config.js` 注入到页面：

- `CONSOLE_PORT`：宿主机端口映射到 Nginx 容器 `:80`（`docker-compose.yml` 使用）。
- `SECRUX_API_BASE_URL`：用户浏览器访问后端的 API 地址。
- `SECRUX_AUTH_MODE_UI`：控制台认证模式（`keycloak` 或 `local`），回退到 `SECRUX_AUTH_MODE`。
- `SECRUX_OIDC_BASE_URL`、`SECRUX_OIDC_REALM`、`SECRUX_OIDC_CLIENT_ID`、`SECRUX_OIDC_SCOPE`：OIDC 配置。
- `SECRUX_APP_VERSION`：版本标识。
