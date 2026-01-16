<p align="center">
  <img src="./logo.svg" alt="Secrux" width="220" />
</p>

# Secrux

[English](README.md)

Secrux 是一个支持多租户、可单机部署的安全治理平台。

特性概览：

- **多租户**：按租户隔离数据与访问控制。
- **开源版本默认集成开源引擎**：代码检查/SAST + SCA（默认 Semgrep/Trivy）。
- **引擎可插拔**：通过引擎镜像与脚本，可快捷接入更多扫描引擎。
- **任务可编排**：任务由多个 stage 组成，不同 stage 组合可构成不同任务模式。
- **全流程 AI 可接管**：每个 stage 都可由 AI 进行接管或辅助执行。
- **后续规划**：将加入自有 SAST 引擎与增强的 AI 审计能力。

核心组件清晰拆分为：

- **控制面**（`apps/server`）：认证/鉴权、任务编排、结果入库、Executor Gateway。
- **控制台**（`apps/web`）：Web UI（部署时由独立 Nginx 容器提供静态站点）。
- **执行机**（`apps/executor`）：Go **二进制**，连接网关、拉起引擎容器并上报日志/结果。
- **扫描引擎**（`apps/engines`）：Semgrep/Trivy 等引擎镜像与运行脚本。
- **AI 服务**（`apps/ai`）：FastAPI 微服务，提供 AI Job/MCP/Agent/知识库等能力。

## 单机快速启动（Quickstart）

1. 复制环境变量模板：

```bash
cp .env.example .env
```

2. 一键启动全栈（基础设施 + 控制面 + 控制台 + AI，优先拉取镜像，失败则本地构建）：

```bash
bash scripts/compose-up.sh
```

2.1.（可选，推荐给远端执行机）为 Executor Gateway 生成 TLS 证书（让执行机可保持 `insecure=false` 进行校验）：

```bash
./scripts/gen-executor-gateway-certs.sh
docker compose -f docker/docker-compose.yml up -d --force-recreate secrux-server
```

3. 访问地址：

- 控制台：`http://localhost:5173`（通过 Nginx 反向代理统一到同一 Origin；浏览器侧使用同源的 `/api/*`、`/auth/*`，避免 CORS）
- API：`http://localhost:8080`（接口文档：`http://localhost:8080/doc.html`）
- Keycloak：`http://localhost:8081`
- AI 健康检查：`http://localhost:5156/health`

提示（服务器部署）：在 `.env` 中设置 `CONSOLE_PORT=80`，然后访问 `http://<host>/`。

4.（可选）在同一台机器启动一个执行机：

```bash
cd apps/executor
cp .env.example .env
go build -o executor-agent .
cp config.temp config.json
# 修改 config.json（server/token）
./executor-agent -config ./config.json
```

TLS/CA 证书/Token 等细节见 `apps/executor/README.zh-CN.md`。

## 配置说明

单机快速启动的 compose 会读取仓库根目录 `.env`（从 `.env.example` 复制）。

- 控制台运行时配置（浏览器侧）：`SECRUX_API_BASE_URL`、`SECRUX_AUTH_MODE_UI`、`SECRUX_OIDC_BASE_URL`、`SECRUX_OIDC_REALM`、`SECRUX_OIDC_CLIENT_ID`、`SECRUX_OIDC_SCOPE`、`SECRUX_APP_VERSION`
- 后端：`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`、`SECRUX_KAFKA_BOOTSTRAP_SERVERS`、`SECRUX_AUTH_MODE`、`SECRUX_AUTH_ISSUER_URI`、`SECRUX_AUTH_AUDIENCE`、`SECRUX_CRYPTO_SECRET`、`SECRUX_AI_DB_URL`、`SECRUX_AI_DB_USERNAME`、`SECRUX_AI_DB_PASSWORD`
- Keycloak 管理端（后端用于用户/角色管理）：`SECRUX_KEYCLOAK_ADMIN_BASE_URL`、`SECRUX_KEYCLOAK_ADMIN_REALM`、`SECRUX_KEYCLOAK_ADMIN_CLIENT_ID`、`SECRUX_KEYCLOAK_ADMIN_CLIENT_SECRET`
- AI 集成：`SECRUX_AI_SERVICE_BASE_URL`、`SECRUX_AI_SERVICE_TOKEN`、`AI_DATABASE_URL`、`SECRUX_AI_LLM_BASE_URL`、`SECRUX_AI_LLM_API_KEY`、`SECRUX_AI_LLM_MODEL`
- Executor Gateway：`EXECUTOR_GATEWAY_ENABLED`、`EXECUTOR_GATEWAY_PORT`、`EXECUTOR_GATEWAY_CERTIFICATE_PATH`、`EXECUTOR_GATEWAY_PRIVATE_KEY_PATH`
- 可选（执行机在远端时）：设置 `SECRUX_EXECUTOR_API_BASE_URL` 为“执行机访问 API 的地址”（默认 `http://localhost:8080`）。

## 默认开发账号（Keycloak 导入 realm）

- Realm：`secrux`
- Client：`secrux-api`（secret：`secrux-api-secret`）
- 用户：`secrux` / `secrux`
- 租户：`4223be89-773e-4321-9531-833fc1cb77af`

## 生产/多节点部署

1. 基础设施节点（Postgres/Kafka/Redis/Keycloak/AI Postgres）：

```bash
cd deploy/production/infra
cp .env.example .env
docker compose up -d
```

2. 控制面节点（后端 + 控制台 + AI）：

```bash
cd deploy/production/control-plane
cp .env.example .env
docker compose up -d
```

3. 执行机节点（按“二进制”部署，agent 本身不需要 Docker 容器）：`apps/executor/README.zh-CN.md`

## 更多文档

- 文档索引：`docs/README.zh-CN.md`
- 本地开发（后端本机运行）：`docs/STARTUP.zh-CN.md`
- 部署指南：`docs/DEPLOYMENT.zh-CN.md`
- 配置与资源参考：`docs/RESOURCE_CONFIGURATION.zh-CN.md`
- 设计文档：`docs/design/secrux设计文档.md`

## 开源协议

Secrux 使用基于 Apache License 2.0 修改的协议（含额外条款）。详见 `LICENSE`。

名称/Logo 使用：`TRADEMARKS.zh-CN.md`

## 参与贡献与安全

- 贡献指南：`CONTRIBUTING.zh-CN.md`（英文：`CONTRIBUTING.md`）
- 安全策略：`SECURITY.zh-CN.md`（英文：`SECURITY.md`）

## 致谢

感谢 SecurityCrux 团队与 Java Chains 团队。

SecurityCrux 团队主要成员：

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

Java Chains 团队主要成员：

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

## 免责声明

请查看 `DISCLAIMER.zh-CN.md`（英文：`DISCLAIMER.md`）。
