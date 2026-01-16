# Secrux 部署文档

[English](DEPLOYMENT.md)

> 本文面向“把 Secrux 跑起来并可用”的场景，覆盖开发/测试/生产的推荐部署方式、依赖组件、端口规划、验证与常见问题。  
> 若只想快速在本机跑后端依赖，可先看 `docs/STARTUP.zh-CN.md`（但请注意：后端 Gradle Wrapper 实际位于 `apps/server/gradlew`）。

## 1. 项目目的与作用（你在部署什么）

Secrux 是一个“可审计、可扩展、支持多租户”的安全扫描平台，核心目标是把不同类型的扫描（SAST/Secrets/IaC/SCA 等）统一到同一套：

- **任务（Task）/阶段（Stage）** 生命周期与编排模型（源码准备→规则准备→扫描执行→结果处理→结果复核…）。
- **统一结果模型**（平台 Finding）与标准产物（如 SARIF、CycloneDX SBOM），支持导出报告。
- **执行面与控制面分离**：控制面负责编排/权限/存储；执行面（Executor Agent）负责在隔离环境运行扫描引擎容器。
- **AI 助手能力**：通过独立 `secrux-ai` 服务提供可配置的 Agent/MCP（工具）与 Knowledge Base（RAG），对 Stage/Finding 做自动预审与建议回传。

适用场景：

- 企业内部代码仓/制品仓的安全基线检查与持续审计（含多租户/多项目隔离）。
- 通过 Executor 将扫描负载从 API 节点卸载到专用扫描节点。
- 在复核阶段引入 AI（可开关、可配置、可审计），并结合团队私有知识库降低幻觉。

## 2. 架构与组件拆分

### 2.1 组件清单（与仓库目录对应）

| 组件 | 目录 | 作用 | 运行形态 |
|---|---|---|---|
| 控制面 API | `apps/server/` | 认证鉴权、任务编排、结果入库、对外 REST API、Executor 网关 | Spring Boot（JDK 21） |
| 控制台 UI | `apps/web/` | 页面与交互（任务/日志/结果/AI 配置等） | Vite + React（构建后静态资源） |
| 执行机 Agent | `apps/executor/` | 连接 Executor Gateway，接收任务并拉起扫描引擎容器，上报日志/结果 | Go 二进制 + Docker |
| 扫描引擎/脚本 | `apps/engines/` | Semgrep/Trivy 等引擎镜像与运行脚本 | Docker 镜像/脚本 |
| AI 微服务 | `apps/ai/` | AI Job、Agent/MCP、Knowledge Base（RAG） | FastAPI + Postgres |
| 单机快速启动栈 | `docker/docker-compose.yml` | Postgres/Kafka/Redis/Keycloak + `secrux-server` + 控制台（容器名 `secrux-console`）+ `secrux-ai` | Docker Compose |

### 2.2 数据与调用链（高层）

```text
Console  --(REST/JWT)-->  secrux-server  --(SQL)--> Postgres
                                |           |
                                |           +--> (Kafka) stage/outbox/events（实现依赖代码路径）
                                |
                                +--(x-platform-token)--> secrux-ai  --(SQL)--> ai-postgres
                                |
                                +--(TLS:5155) <--- secrux-executor  --(Docker)--> engines
                                |
                                +--(HTTP:8080, X-Executor-Token) <--- secrux-executor（下载上传的源码/SBOM）
```

## 3. 端口、域名与网络要求

### 3.1 默认端口（本仓库 `docker/docker-compose.yml`）

| 服务 | 端口 | 说明 |
|---|---:|---|
| `secrux-server` | 8080 | 控制面 API（Knife4j：`/doc.html`；Actuator：`/actuator/*`） |
| `secrux-console` | 5173 | 控制台 UI（由独立 Nginx 容器提供静态站点） |
| Keycloak | 8081 | OIDC 身份提供方（dev realm 由 `keycloak/realm-secrux.json` 导入） |
| Executor Gateway | 5155 | executor-agent 连接入口（Spring local profile 默认启用） |
| AI Service | 5156 | `secrux-ai` FastAPI（健康检查 `/health`） |
| Postgres | 5432 | 主库（默认 DB/User/Pass: `secrux`） |
| Kafka（Host） | 19092 | 本机访问端口（避免 9092 冲突） |
| Kafka（Docker 网络） | 29092 | 容器间访问（`kafka:29092`） |
| Zookeeper | 2181 | Kafka 依赖 |
| Redis | 6379 | 可选缓存占位 |

### 3.2 网络连通性（生产尤其重要）

- 控制台必须能访问 `secrux-server` 与 Keycloak。
- 执行机必须能访问：
  - Executor Gateway（`5155/TCP`，建议仅内网开放）。
  - `secrux-server` 的上传下载接口（默认 `http(s)://<api>/executor/uploads/*`）。
  - 代码仓库（Git clone）、镜像仓库（拉取引擎镜像）、以及扫描引擎所需的外部依赖源（如 Maven 仓库、Trivy DB 镜像源等）。
- `secrux-server` 必须能访问：Postgres、Kafka、Redis、Keycloak（以及可选 OPA）。
- `secrux-ai` 必须能访问：ai-postgres（以及可选 LLM/RAG 后端）。

## 4. 本地开发部署（推荐流程）

### 4.1 前置条件

- Docker + Docker Compose
- JDK 21
- Node.js 18+（用于 `apps/web`）
- Go 1.22+（用于 `apps/executor`）

### 4.2 启动依赖（Postgres/Kafka/Redis/Keycloak/AI）

在仓库根目录：

```bash
docker compose -f docker/docker-compose.yml up -d postgres redis zookeeper kafka keycloak
# 可选（需要 AI 服务时再启动）：
# docker compose -f docker/docker-compose.yml up -d ai-postgres ai-service
docker compose -f docker/docker-compose.yml ps
```

> 如需“一键全栈启动”（控制面 + 控制台 + AI），请看仓库根目录的 `README.md`。

### 4.3 启动后端 API（Spring Boot）

在 `apps/server/`：

```bash
cd apps/server
./gradlew flywayMigrate
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

验证：

- API: `http://localhost:8080`
- Knife4j: `http://localhost:8080/doc.html`
- Health: `http://localhost:8080/actuator/health`

### 4.4 启动控制台（Vite Dev Server）

在 `apps/web/`：

```bash
cd apps/web
npm install
npm run dev
```

控制台配置（本地默认值可不配；需要覆盖时写到 `apps/web/.env.local`）：

- `VITE_API_BASE_URL`（默认 `http://localhost:8080`）
- `VITE_OIDC_BASE_URL`（默认 `http://localhost:8081`）
- `VITE_OIDC_REALM`（默认 `secrux`）
- `VITE_OIDC_CLIENT_ID`（默认 `secrux-console`）

### 4.5 获取本地 Keycloak Token（dev-only）

`docs/STARTUP.zh-CN.md` 里提供了可直接复制的 curl 示例。默认 dev realm 中内置了 `secrux/secrux` 的账号与 `secrux-api` 客户端。

### 4.6 注册 Executor 并启动执行机（apps/executor）

1) 注册 executor（需要管理员权限的 Bearer Token）：

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

返回里包含 `executorId`；然后查询 token：

```bash
curl "http://localhost:8080/executors/<executorId>/token" \
  -H "Authorization: Bearer $KC_TOKEN"
```

2) 准备执行机配置文件：优先复制 `apps/executor/config.temp`，把 token 与网关地址填进去（不要提交真实 token）：

```bash
cp apps/executor/config.temp /tmp/secrux-agent.json
```

3) 启动 executor-agent（需要本机 Docker Engine，可访问 `/var/run/docker.sock`）：

```bash
cd apps/executor
go build -o executor-agent .
./executor-agent -config /tmp/secrux-agent.json
```

4) 引擎镜像准备（可选）

- 如果你希望使用本地镜像（与 `apps/executor/config.json` 的示例一致），可在 `apps/engines/` 构建：

```bash
docker build -t secrux/semgrep:local -f apps/engines/Dockerfile.semgrep apps/engines
docker build -t secrux/trivy:local -f apps/engines/Dockerfile.trivy apps/engines
```

> 生产建议使用受控镜像仓库（例如 `ghcr.io/...` 或企业私有 Harbor），并通过 `engineImages` 显式映射。

## 5. 单机/测试环境部署（无 K8s）

仓库内的 `docker/docker-compose.yml` 可用于“单机快速启动”全栈（含 UI/AI）。在测试环境中也可以只启动其中的基础设施服务（Postgres/Kafka/Keycloak 等），然后将各模块按以下方式部署：

- `apps/server`：构建 fat jar，以 systemd 或容器运行。
- `apps/web`：`npm run build` 后产出静态资源，交给 Nginx/Caddy/S3 静态托管。
- `apps/executor`：安装为 systemd 服务（需要宿主机 Docker Engine）。
- `apps/ai`：可继续沿用 compose 中的 `ai-service` 方式，或按企业规范部署到独立主机/容器平台。

### 5.1 构建 `apps/server`

```bash
cd apps/server
./gradlew test
./gradlew bootJar
ls -la build/libs
```

运行（示例）：

```bash
java -jar build/libs/secrux-server-*.jar
```

### 5.2 构建 `apps/web`

```bash
cd apps/web
npm ci
npm run build
```

将 `apps/web/dist/` 部署为静态站点；生产环境建议通过反向代理统一域名与 HTTPS。

## 6. 生产部署建议（关键点清单）

### 6.1 必做安全项

- 固定并保护加密密钥：设置 `SECRUX_CRYPTO_SECRET`（见 `com.secrux.config.SecruxCryptoProperties`）。一旦更换将无法解密已保存的仓库凭证/Token。
- `SECRUX_AI_SERVICE_TOKEN` 作为服务间凭证：仅用于 `secrux-server -> secrux-ai`，放入 Secret 管理系统，避免写入镜像或仓库。
- Executor token（`/executors/*/token`）视为密码：只发给对应执行机，泄露需要在平台侧轮换并更新 agent 配置。
- 启用 TLS：至少为对外 API、Keycloak、Executor Gateway 提供 TLS；Executor Gateway 建议仅内网开放并使用受信 CA 证书（agent 端 `insecure=false`）。

### 6.2 认证与鉴权模式

- 开发：`SPRING_PROFILES_ACTIVE=local`（Keycloak 真实 token，且 `secrux.authz` 默认关闭便于迭代）。
- 生产：建议 `secrux.auth.mode=KEYCLOAK`，并按需启用 `secrux.authz.enabled=true` 对接 OPA PDP（`SECRUX_AUTHZ_OPA_URL` / `SECRUX_AUTHZ_POLICY_PATH`）。

### 6.3 数据持久化与备份

- 至少需要备份：
  - 控制面 Postgres（任务/结果/仓库/执行机/工单配置等）。
  - AI Service 的 ai-postgres（knowledge entries、agent/mcp 配置、ai job 等）。
- 备份策略必须与 `SECRUX_CRYPTO_SECRET` 的备份绑定，否则恢复后无法解密敏感字段。

### 6.4 执行机与容量规划

- Executor 建议独立节点池（按 CPU/内存/网络/磁盘规划），并通过 `labels` 做能力标记（例如 `arch=x86_64`、`zone=...`、`policy=...`）。
- `secrux.executor.dispatch.api-base-url`（环境变量 `SECRUX_EXECUTOR_API_BASE_URL`）必须是执行机可访问的 API 基址，否则无法下载上传的源码/SBOM。

## 7. 部署后验证清单（建议按顺序）

1. `docker compose -f docker/docker-compose.yml ps`：依赖服务健康。
2. `GET http://<api>/actuator/health`：控制面健康。
3. `GET http://<ai>/health`：AI service 健康。
4. Keycloak 登录与获取 token 正常（Console 可跳转登录）。
5. 调用 `POST /executors/register` 并启动 executor-agent，确认 executor 状态从 `REGISTERED/OFFLINE` 变为 `READY`。
6. 在控制台创建项目/仓库/任务，观察 Stage 日志与结果入库。

## 8. 常见问题（Troubleshooting）

- **后端命令找不到 `./gradlew`**：请在 `apps/server/` 目录执行（Wrapper 位于 `apps/server/gradlew`）。
- **executor-agent 无法连接 5155**：确认后端启用了 `executor.gateway.enabled=true`（本地 profile 已启用），并检查防火墙/证书配置。
- **executor-agent 下载 upload 失败**：检查 `SECRUX_EXECUTOR_API_BASE_URL` 是否为执行机可达地址；以及 `X-Executor-Token` 是否匹配平台下发的 token。
- **仓库克隆需要凭证**：请在“Repository”对话框里选择 BASIC/TOKEN 并填写；平台会加密存储并在下发任务时提供给执行机。
