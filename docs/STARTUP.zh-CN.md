# Secrux 本地环境启动指南

[English](STARTUP.md)

本文档说明如何在本机启动依赖服务（数据库、Kafka、Redis 等）并本地运行 Secrux 后端。

如果你需要全栈部署（控制台 + 执行机 + AI 服务）以及容量/资源配置建议，请参考：

- `docs/DEPLOYMENT.zh-CN.md`
- `docs/RESOURCE_CONFIGURATION.zh-CN.md`

## 1. 前置条件

- Docker 与 Docker Compose
- JDK 21（确保 `java -version` 显示 21）
- `apps/server/gradlew` 可执行（仓库已自带）

## 2. 启动基础设施依赖

在仓库根目录执行：

```bash
docker compose -f docker/docker-compose.yml up -d postgres redis zookeeper kafka keycloak
```

可选（AI 服务 + AI Postgres）：

```bash
docker compose -f docker/docker-compose.yml up -d ai-postgres ai-service
```

如需一键启动全栈（后端 + 控制台 + AI），请参考仓库根目录 `README.zh-CN.md`。

基础设施服务与端口：

| 服务 | 端口 | 说明 |
|------|------|------|
| Postgres | 5432 | DB `secrux`，用户名/密码 `secrux` |
| Zookeeper | 2181 | Kafka 依赖 |
| Kafka | 19092 | 本机 PLAINTEXT 监听（避免与常见的 9092 冲突） |
| Redis | 6379 | 可选缓存占位 |
| Keycloak | 8081 | OIDC 身份提供方；dev realm 会从 `keycloak/realm-secrux.json` 自动导入 |
| AI service（可选） | 5156 | FastAPI 服务；健康检查 `/health` |
| AI Postgres（可选） |（内部）| AI 服务专用数据库 |

你可以查看容器健康状态：

```bash
docker compose -f docker/docker-compose.yml ps
```

## 3. 运行数据库迁移

当 Postgres 健康后执行：

```bash
cd apps/server
./gradlew flywayMigrate
```

该命令会执行 `src/main/resources/db/migration` 下的迁移脚本并初始化表结构。

## 4. 启动 Spring Boot 服务

```bash
./gradlew bootRun
```

API 默认监听 `http://localhost:8080`。Knife4j：`http://localhost:8080/doc.html`。

停止应用：按 `Ctrl+C`。

## 5. 认证模式

- **本地开发（Docker Keycloak）**：使用 `SPRING_PROFILES_ACTIVE=local` 运行（或在 `bootRun` 增加 `--spring.profiles.active=local`）。  
  该 profile 期望从内置 Keycloak realm（`http://localhost:8081/realms/secrux`）签发的真实 OIDC token，同时默认关闭 `secrux.authz` 以便快速迭代。
- **Legacy HMAC 模式**：如果你仍需要测试自签 JWT（无需 IdP），可覆盖 `SECRUX_AUTH_MODE=LOCAL`。
- **Keycloak + OPA（生产）**：使用默认 profile，并提供：
  - `SECRUX_AUTH_ISSUER_URI` → Keycloak realm issuer（例如 `https://sso.company.com/realms/secrux`）
  - `SECRUX_AUTH_AUDIENCE` → access token 中期望的 audience / client ID
  - `SECRUX_AUTHZ_ENABLED=true`，并配置 `SECRUX_AUTHZ_OPA_URL` / `SECRUX_AUTHZ_POLICY_PATH` 指向 PDP 服务
  - `SECRUX_CRYPTO_SECRET` → 存储密文的加密密钥（必配；更换需谨慎）

当启用 `secrux.authz` 时，每次 controller 调用都会向 OPA 发送 `{subject, action, resource, context}`。为安全起见，当 OPA 不可用时 Secrux 会默认拒绝请求（fail closed）。

## 6. 从 Keycloak 获取本地开发 Token

dev realm 内置了与后端默认配置匹配的租户与用户：

| 项目 | 值 |
|------|----|
| Realm | `secrux` |
| Client ID | `secrux-api` |
| Client secret | `secrux-api-secret` |
| 用户名 | `secrux` |
| 密码 | `secrux` |
| Tenant UUID | `4223be89-773e-4321-9531-833fc1cb77af` |

1. 确认 Keycloak 已启动：`docker compose -f docker/docker-compose.yml up -d keycloak`（通常已包含在 `docker compose -f docker/docker-compose.yml up -d` 中）。
2. 使用 Direct Access Grant 获取 token：

```bash
export KC_TOKEN=$(curl -s -X POST http://localhost:8081/realms/secrux/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=secrux-api" \
  -d "client_secret=secrux-api-secret" \
  -d "grant_type=password" \
  -d "username=secrux" \
  -d "password=secrux" | jq -r .access_token)
```

3. 使用 Bearer token 调用 API（示例：列出项目）：

```bash
curl -H "Authorization: Bearer $KC_TOKEN" http://localhost:8080/projects
```

你应该能拿到 `ApiResponse` 的 JSON 返回。上述账号仅用于开发环境；若共享环境请及时更换/轮换。

## 7. 常用环境变量

如有需要，你可以覆盖连接信息：

| 变量 | 默认值 | 说明 |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/secrux` | Postgres JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `secrux` | DB 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | `secrux` | DB 密码 |
| `SECRUX_AUTH_ISSUER_URI` | `http://localhost:8081/realms/secrux` | Keycloak realm issuer |
| `SECRUX_AUTH_AUDIENCE` | `secrux-api` | 期望的 audience / clientId |
| `SECRUX_AUTHZ_ENABLED` | `false` | 是否启用 OPA 授权 |
| `SECRUX_AUTHZ_OPA_URL` | `http://localhost:8181` | OPA 基址 |
| `SECRUX_AUTHZ_POLICY_PATH` | `/v1/data/secrux/allow` | 调用的策略路径 |
| `SECRUX_CRYPTO_SECRET` | *(必配)* | 存储密文的加密密钥 |
| `SECRUX_AI_SERVICE_BASE_URL` | `http://localhost:5156` | AI 服务地址（可选） |
| `SECRUX_AI_SERVICE_TOKEN` | `local-dev-token` | `secrux-server -> secrux-ai` 的服务间 token |

## 8. 停止与清理

```bash
docker compose -f docker/docker-compose.yml down
```

如果需要完全重置（例如合并/重做 Flyway 迁移后），也可以删除 volumes：

```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
```

使用 `-v` 会删除 Postgres 数据卷。
