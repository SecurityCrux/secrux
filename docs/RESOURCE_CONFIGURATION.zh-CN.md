# Secrux 资源配置文档

[English](RESOURCE_CONFIGURATION.md)

> 本文聚焦“上线可用需要配什么、每个组件要多少资源、怎么扩容与调优”。  
> 具体的启动步骤请配合 `docs/DEPLOYMENT.zh-CN.md`。

## 1. 配置入口总览（从哪里配）

| 范围 | 入口 | 典型用途 |
|---|---|---|
| 控制面（Spring Boot） | `apps/server/src/main/resources/application.yml` + 环境变量覆盖 | DB/Kafka/Auth/AI/Executor/存储路径等 |
| 控制台（Vite） | `apps/web/.env.local` 或部署时注入 `VITE_*` | API base、OIDC 参数 |
| AI 服务（FastAPI） | 根目录 `.env`（compose）或部署时注入环境变量 | ai-service token、DB、LLM、RAG、调试 |
| 执行机（Go） | `apps/executor/config.temp`（模板）+ `-config` | gateway、token、引擎镜像、Trivy 行为 |

## 2. 控制面关键配置（apps/server）

### 2.1 必配（生产必须设置）

| 配置项（Spring 属性） | 环境变量写法 | 说明 |
|---|---|---|
| `secrux.crypto.secret` | `SECRUX_CRYPTO_SECRET` | 用于加密仓库凭证、Semgrep Token、工单系统 token 等；更换会导致历史密文无法解密 |
| `spring.datasource.url` / `username` / `password` | `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 控制面主库 |
| `spring.kafka.bootstrap-servers` | `SECRUX_KAFKA_BOOTSTRAP_SERVERS` | Kafka 地址（本地默认 `127.0.0.1:19092`） |
| `secrux.auth.mode` | `SECRUX_AUTH_MODE` | `LOCAL` / `KEYCLOAK`（生产通常 `KEYCLOAK`） |
| `secrux.auth.issuer-uri` / `audience` | `SECRUX_AUTH_ISSUER_URI` / `SECRUX_AUTH_AUDIENCE` | Keycloak Realm issuer 与受众 |
| `secrux.ai.service.base-url` / `token` | `SECRUX_AI_SERVICE_BASE_URL` / `SECRUX_AI_SERVICE_TOKEN` | `secrux-server -> secrux-ai` 调用凭证（必须一致） |
| `secrux.executor.dispatch.api-base-url` | `SECRUX_EXECUTOR_API_BASE_URL` | executor-agent 用于下载上传内容的 API 基址（必须从执行机可访问） |

> 注：环境变量到 Spring 属性的映射遵循 Spring Boot 的 relaxed binding（`.`/`-`/`_` 互通，大小写不敏感）。

### 2.2 常配（按环境选择）

| 配置项 | 环境变量写法 | 说明 |
|---|---|---|
| `secrux.authz.enabled` | `SECRUX_AUTHZ_ENABLED` | 对接 OPA 进行授权（ABAC/RBAC），默认 `false` |
| `secrux.authz.opa-url` / `policy-path` | `SECRUX_AUTHZ_OPA_URL` / `SECRUX_AUTHZ_POLICY_PATH` | OPA PDP 地址与策略路径 |
| `secrux.upload.root` | `SECRUX_UPLOAD_ROOT` | 上传文件落盘路径（需要持久化/共享时请挂载卷或替换为对象存储实现） |
| `secrux.workspace.root` | `SECRUX_WORKSPACE_ROOT` | 本地准备 workspace 的根目录（主要用于本机扫描/调试） |
| `secrux.keycloak.admin.*` | `SECRUX_KEYCLOAK_ADMIN_*` | 控制面访问 Keycloak Admin API（用于用户管理等能力） |

### 2.3 AI 配置库（spring.datasource.ai / spring.flyway.ai）

控制面内部还维护了一套 **AI 配置库**（用于保存租户级 AI Client 配置，例如 OpenAI/Azure OpenAI 的 `baseUrl/model/apiKey`，并在触发 AI Review 时下发给 `secrux-ai`）。

这套库通过 `spring.datasource.ai.*` 配置，默认指向同一个 Postgres（`secrux`），以减少组件数量；如需隔离也可以配置为独立 DB。

| 配置项（Spring 属性） | 环境变量写法 | 默认值（见 `application.yml`） | 说明 |
|---|---|---|---|
| `spring.datasource.ai.url` | `SECRUX_AI_DB_URL` | `jdbc:postgresql://localhost:5432/secrux` | AI 配置库 JDBC URL |
| `spring.datasource.ai.username` | `SECRUX_AI_DB_USERNAME` | `secrux` | 用户名 |
| `spring.datasource.ai.password` | `SECRUX_AI_DB_PASSWORD` | `secrux` | 密码 |
| `spring.flyway.ai.*` | `SPRING_FLYWAY_AI_*` | 已在 `application.yml` 预置 | AI 配置库迁移参数（locations/table 等） |

> 注意：这里的 `SECRUX_AI_DB_*` **不是** `secrux-ai` 的 `AI_DATABASE_URL`。`secrux-ai` 有自己的 Postgres（ai-postgres），不要把两者混用。

### 2.4 Executor Gateway（控制面内置）

本地 profile 通过 `executor.gateway.*` 启用网关（见 `apps/server/src/main/resources/application-local.yml`）。

| 配置项 | 环境变量写法 | 说明 |
|---|---|---|
| `executor.gateway.enabled` | `EXECUTOR_GATEWAY_ENABLED` | 是否启用网关（默认 `false`） |
| `executor.gateway.port` | `EXECUTOR_GATEWAY_PORT` | 默认 `5155` |
| `executor.gateway.certificate-path` / `private-key-path` | `EXECUTOR_GATEWAY_CERTIFICATE_PATH` / `EXECUTOR_GATEWAY_PRIVATE_KEY_PATH` | PEM 证书与私钥；缺失时服务端会生成自签（dev 用） |
| `executor.gateway.max-frame-bytes` | `EXECUTOR_GATEWAY_MAX_FRAME_BYTES` | 单帧最大大小（默认 5 MiB） |

## 3. AI 服务关键配置（apps/ai / ai-service）

| 环境变量 | 默认/示例 | 说明 |
|---|---|---|
| `SECRUX_AI_SERVICE_TOKEN` | `local-dev-token` | 必须与控制面 `secrux.ai.service.token` 一致 |
| `AI_DATABASE_URL` | `postgresql+psycopg://...` | AI service 独立 Postgres（不与控制面共享） |
| `SECRUX_AI_LLM_BASE_URL` / `SECRUX_AI_LLM_API_KEY` / `SECRUX_AI_LLM_MODEL` | 空 | 全局 LLM 调用配置（空则禁用实时 LLM 调用） |
| `AI_RAG_PROVIDER` | `local` / `ragflow` | Knowledge Base 检索后端选择（见 `docs/ragflow-switch-guide.zh-CN.md`） |
| `SECRUX_AI_PROMPT_DUMP*` | 见根目录 `.env` | 调试用 prompt dump（生产建议关闭或严格控制） |

## 4. 控制台关键配置（apps/web）

| 环境变量 | 默认值（代码 fallback） | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | 控制面 API 地址 |
| `VITE_OIDC_BASE_URL` | `http://localhost:8081` | Keycloak base URL |
| `VITE_OIDC_REALM` | `secrux` | Realm |
| `VITE_OIDC_CLIENT_ID` | `secrux-console` | OIDC clientId |
| `VITE_OIDC_SCOPE` | `openid` | scope |
| `VITE_APP_VERSION` | `dev` | 页面显示版本/埋点用 |

## 5. 执行机配置（apps/executor）

配置文件建议从 `apps/executor/config.temp` 复制（模板中已包含字段说明），核心字段：

| 字段 | 说明 |
|---|---|
| `server` | Executor Gateway 地址（如 `gateway.secrux.internal:5155`） |
| `serverName` | TLS 校验证书域名（可选；默认使用 `server` 的 host） |
| `caCertPath` | CA/自签证书 PEM 路径（可选；用于 `insecure=false` 时信任网关证书） |
| `token` | 控制面注册返回的 executor token（视为密码） |
| `insecure` | 是否跳过 TLS 校验（dev-only；生产必须 `false`） |
| `engineImages` | 引擎名→镜像地址映射（`semgrep`/`trivy`…） |
| `trivy.*` | Trivy 运行参数与缓存挂载策略（降低网络依赖、规避废弃 Maven 仓库） |

## 6. 资源规划（CPU/内存/磁盘）

> 下表是“可用起步值”，不是上限。实际消耗主要受：并发任务数、单次扫描仓库体量、引擎类型（Trivy/Semgrep）、保留周期影响。

### 6.1 最小可用（PoC/单机）

| 组件 | CPU | 内存 | 磁盘/IO |
|---|---:|---:|---|
| `secrux-server` | 2 vCPU | 2–4 GiB | 日志 + 上传目录（SSD 推荐） |
| Postgres | 2 vCPU | 4–8 GiB | 50+ GiB（随结果增长） |
| Kafka + ZK | 2 vCPU | 4–6 GiB | 20+ GiB（按保留策略） |
| Keycloak | 1 vCPU | 1–2 GiB | 1+ GiB |
| Redis | 0.5–1 vCPU | 0.5–1 GiB | 可忽略 |
| `secrux-ai` | 1 vCPU | 1–2 GiB | 1+ GiB |
| ai-postgres | 1 vCPU | 1–2 GiB | 10+ GiB（知识库/Job） |
| 每台 executor | 4+ vCPU | 8+ GiB | Docker 镜像缓存 + 引擎缓存（SSD 强烈建议） |

### 6.2 小团队（推荐起点）

- 控制面 API：2–4 vCPU / 4–8 GiB（可水平扩展；需共享 DB/Kafka）
- Postgres：4 vCPU / 16 GiB（独立 SSD，设置备份与监控）
- Kafka：4 vCPU / 16 GiB（或使用托管 Kafka）
- Executor：按“并发扫描数 × 单任务资源上限”规划；建议独立节点池

## 7. 调优建议（常见瓶颈）

### 7.1 数据库（Postgres）

- 监控连接数与慢 SQL；必要时调 Hikari `maximumPoolSize`（通过 Spring datasource hikari 配置）。
- 扫描结果与日志增长快：规划归档/保留策略（按项目/任务粒度）。
- 备份恢复演练：恢复后校验 `SECRUX_CRYPTO_SECRET` 能解密历史数据。

### 7.2 Kafka

- 若启用事件驱动编排/消费：设置合理的 topic 分区与保留期，避免磁盘被占满。
- 本地 compose 为单副本配置，生产需按集群与副本数调整。

### 7.3 Executor 与引擎容器

- Trivy/Semgrep 都可能触发大量网络访问；建议：
  - 预拉取镜像与 DB 缓存（Trivy cache 持久化）。
  - 挂载 Maven 缓存与 settings（见 `apps/executor` 的 trivy 配置）。
  - 为引擎容器设置 CPU/内存上限（平台下发的 `cpuLimit/memoryLimitMb`）。

### 7.4 AI Service

- 若启用 LLM：LLM 端到端延迟会显著影响 review 完成时间；建议把 job 设计成异步、并设置合理超时/并发。
- Knowledge Base 若切换到向量检索（如 RAGFlow）：把检索后端容量与隔离（按 tenantId）纳入规划。

## 8. 生产上线前检查（Checklist）

- [ ] `SECRUX_CRYPTO_SECRET` 已设置且已备份（不可随意更换）
- [ ] `SECRUX_AI_SERVICE_TOKEN` 已放入 Secret 管理并与 ai-service 一致
- [ ] executor token 已按机器最小权限分发、可轮换
- [ ] 对外 API/Keycloak/网关均启用 TLS
- [ ] Postgres 与 ai-postgres 已配置备份与监控
- [ ] executor 节点具备足够磁盘缓存与网络出口策略（依赖下载/镜像拉取）
