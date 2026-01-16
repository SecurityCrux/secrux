# Secrux Server（后端）

[English](README.md)

`secrux-server` 是 Secrux 的 Spring Boot 控制面 API：负责多租户认证/鉴权、任务编排、数据入库，以及供 `executor-agent` 连接的 Executor Gateway。

## 开发环境要求

- JDK 21
- Docker（推荐用于启动 Postgres/Kafka/Keycloak）

## 本地开发（后端本机运行）

1. 在仓库根目录启动依赖（Postgres/Redis/Kafka/Keycloak）：

```bash
docker compose up -d postgres redis zookeeper kafka keycloak
```

2. 启动后端：

```bash
cd secrux-server
export SPRING_PROFILES_ACTIVE=local
./gradlew bootRun
```

3. 运行测试：

```bash
./gradlew test
```

## 单模块部署（Docker Compose）

该方式会在一个 compose 项目中启动：后端 + Postgres + Kafka + Keycloak。

```bash
cd secrux-server
cp .env.example .env
docker compose up -d
docker compose ps
```

默认端口：
- API：`http://localhost:8080`（接口文档：`http://localhost:8080/doc.html`）
- Keycloak：`http://localhost:8081`
- Kafka（宿主机）：`127.0.0.1:19092`
- Postgres：`localhost:5432`

## 配置说明

复制 `.env.example` 为 `.env` 后按需调整。常用配置项：

- 认证：`SECRUX_AUTH_MODE`、`SECRUX_AUTH_ISSUER_URI`、`SECRUX_AUTH_AUDIENCE`
- Keycloak 管理端（用户/角色管理）：`SECRUX_KEYCLOAK_ADMIN_BASE_URL`、`SECRUX_KEYCLOAK_ADMIN_CLIENT_SECRET`
- 加密密钥：`SECRUX_CRYPTO_SECRET`（生产必填）
- Kafka：`SECRUX_KAFKA_BOOTSTRAP_SERVERS`
- AI 集成（可选）：`SECRUX_AI_SERVICE_BASE_URL`、`SECRUX_AI_SERVICE_TOKEN`
- Executor Gateway：`EXECUTOR_GATEWAY_ENABLED`、`EXECUTOR_GATEWAY_PORT`

## 配置项解析

### 端口（compose）

- `SECRUX_SERVER_PORT`：宿主机端口映射到 API 容器的 `:8080`。
- `EXECUTOR_GATEWAY_PORT`：宿主机端口映射到网关监听端口（容器 `:5155`）。
- `KEYCLOAK_PORT`、`POSTGRES_PORT`、`KAFKA_HOST_PORT`：`secrux-server/docker-compose.yml` 中基础设施服务的宿主机端口。

### 数据库

- `SPRING_DATASOURCE_URL`：JDBC 地址（compose 内通常为 `jdbc:postgresql://postgres:5432/secrux`）。
- `SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`：数据库账号密码。

### Kafka

- `SECRUX_KAFKA_BOOTSTRAP_SERVERS`：Kafka bootstrap servers（compose 内通常为 `kafka:29092`）。

### 认证

- `SECRUX_AUTH_MODE`：`KEYCLOAK` 或 `LOCAL`（后端认证模式）。
- `SECRUX_AUTH_ISSUER_URI`：`KEYCLOAK` 模式下的 issuer（示例：`http://keycloak:8081/realms/secrux`）。
- `SECRUX_AUTH_AUDIENCE`：访问令牌中要求的 audience/clientId（示例：`secrux-api`）。

### Keycloak 管理端（IAM 管理）

后端在 Keycloak 模式下用于管理用户/角色。

- `SECRUX_KEYCLOAK_ADMIN_BASE_URL`：Keycloak 基础 URL（示例：`http://keycloak:8081`）。
- `SECRUX_KEYCLOAK_ADMIN_REALM`：Realm 名称（默认 `secrux`）。
- `SECRUX_KEYCLOAK_ADMIN_CLIENT_ID`：管理客户端 ID（默认 `secrux-admin`）。
- `SECRUX_KEYCLOAK_ADMIN_CLIENT_SECRET`：管理客户端 secret（必须与 realm 导入一致）。

### 执行机调度（执行机访问 API 的地址）

- `SECRUX_EXECUTOR_API_BASE_URL`：下发到执行机任务 payload 的 API Base URL（执行机用于下载/上传产物），默认 `http://localhost:8080`。

### 加密密钥

- `SECRUX_CRYPTO_SECRET`：用于加密存储凭证的密钥（生产环境必须固定且谨慎轮换）。

### AI 集成（可选）

- `SECRUX_AI_SERVICE_BASE_URL`：AI 服务地址。
- `SECRUX_AI_SERVICE_TOKEN`：与 `secrux-ai` 共享的服务间 token。

### Executor Gateway（TLS）

- `EXECUTOR_GATEWAY_ENABLED`：是否启用网关监听。
- `EXECUTOR_GATEWAY_CERTIFICATE_PATH`、`EXECUTOR_GATEWAY_PRIVATE_KEY_PATH`：可选 PEM 证书/私钥；为空时后端启动时会生成自签名证书。
