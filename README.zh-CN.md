# Secrux Executor Agent（执行机）

[English](README.md)

执行机负责连接 Secrux 控制面的 Executor Gateway（TLS），上报心跳，接收 `task_assign` 指令，并在本机拉起扫描引擎容器（Semgrep/Trivy 等），把日志与结果回传到控制面。

它是一个 **Go 二进制程序**。运行执行机的主机需要：

- 已安装 Docker 且 Docker daemon 正常运行（用于启动引擎容器）
- 网络可达：
  - Executor Gateway（默认 `:5155`）
  - Secrux Server API（用于下载上传内容 `/executor/uploads/*` 等）

## 配置

1. **配置文件（推荐）**  
   将 `config.temp` 复制为 `config.json` 并填写真实配置，然后用 `-config ./config.json` 启动。

   示例（JSON）：

   ```jsonc
   {
     "server": "gateway.secrux.internal:5155",
     "serverName": "gateway.secrux.internal",
     "caCertPath": "../certs/executor-gateway/gateway-ca.crt",
     "token": "EXECUTOR_TOKEN",
     "insecure": false,
     "engineImages": {
       "semgrep": "ghcr.io/secrux/semgrep:latest",
       "trivy": "ghcr.io/secrux/trivy:latest"
     },
     "trivy": {
       "sanitizePomRepositories": true,
       "filesystemCopyMode": "auto",
       "timeoutSec": 1200,
       "mavenRepositoryPath": "~/.m2/repository",
       "mavenSettingsPath": "~/.m2/settings.xml",
       "cacheHostPath": "~/.cache/secrux/trivy",
       "inheritProxyEnv": true
     }
   }
   ```

   注意：`token` 视为密码，泄露必须在平台侧轮换并更新执行机配置。

2. **命令行与环境变量**  
   - `-server` / `-server-name` / `-ca-cert` / `-token` / `-insecure` 可覆盖配置文件。  
   - 若未提供 `-token`，会读取 `EXECUTOR_TOKEN` 环境变量（适合配合 `.env` 或 systemd 的 `EnvironmentFile`）。  
   - `ENGINE_IMAGE_MAP`（`engine=image,...`）与 `ENGINE_SEMGREP_IMAGE`/`ENGINE_TRIVY_IMAGE` 可覆盖引擎镜像映射。

3. **优先级**  
   - 连接/TLS：默认值 → `config.json` → 命令行参数  
   - Token：`-token` → `config.json.token` → `EXECUTOR_TOKEN`  
   - 引擎镜像：`config.json.engineImages` → `ENGINE_IMAGE_MAP` → `ENGINE_SEMGREP_IMAGE`/`ENGINE_TRIVY_IMAGE` → 默认值  
   - Trivy 配置：默认值 → `config.json.trivy`

## Gateway TLS 证书

当 `insecure=false` 时，执行机会校验网关证书是否与 `serverName` 匹配（SAN/主机名校验）。

在单机 quickstart 场景下，你可以生成一套“带 SAN”的证书并让后端使用它：

```bash
# 在仓库根目录执行
./gen-executor-gateway-certs.sh
docker compose up -d --force-recreate secrux-server
```

然后把 `caCertPath` 指向生成的 CA（推荐）：`../certs/executor-gateway/gateway-ca.crt`，
并确保 `serverName` 与证书 SAN 中的某个域名一致（默认包含 `gateway.secrux.internal` 与 `localhost`）。

## 配置项说明

### `config.json`

- `server`（string）：Executor Gateway 地址，格式 `host:port`（TCP + TLS）。
- `serverName`（string）：TLS 校验用的服务端名称（SNI + 主机名校验）。当你用 IP/别名连接，但证书颁发给某个域名时，需要设置为证书中的域名。
- `caCertPath`（string）：CA 证书（PEM）路径。会把该证书追加到系统信任链中，用于校验网关证书。
- `token`（string）：控制面下发的执行机 token（视为密码）。
- `insecure`（bool）：**仅开发/临时使用**。跳过 TLS 证书校验（`InsecureSkipVerify=true`）。连接仍然是 TLS 加密的，但无法验证网关身份（有中间人风险）。当 `true` 时会忽略 `caCertPath`。
- `engineImages`（object）：`engine -> docker image` 映射。当任务 payload 未显式指定 `image` 时使用；key 不区分大小写。
- `trivy`（object）：Trivy 执行相关配置：
  - `sanitizePomRepositories`（bool）：扫描前清理 `pom.xml` 中的 Maven 仓库地址（移除黑名单 host），降低依赖解析失败/超时概率。
  - `bannedMavenRepoHosts`（string[]）：需要从 Maven 仓库 URL 中移除的 host（默认包含 Bintray/JCenter 等）。
  - `filesystemCopyMode`（`auto` | `always` | `never`）：是否在修改 `pom.xml` 前复制一份扫描目录，避免直接改动源目录。
  - `timeoutSec`（number）：设置后覆盖 Trivy 任务超时（秒），默认使用任务超时或 20 分钟。
  - `mavenRepositoryPath`（string）：宿主机 Maven 本地仓库路径（只读挂载到容器 `/root/.m2/repository`）。
  - `mavenSettingsPath`（string）：宿主机 Maven `settings.xml` 路径（只读挂载到容器 `/root/.m2/settings.xml`）。
  - `cacheHostPath`（string）：宿主机 Trivy 缓存目录（挂载到容器 `/tmp/trivy-cache`，用于复用 DB/缓存）。
  - `inheritProxyEnv`（bool）：当任务 env 未显式设置时，把宿主机的 `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` 传入 Trivy 容器。

### 环境变量

- `EXECUTOR_TOKEN`：未传 `-token` 时用于填充 token。
- `ENGINE_IMAGE_MAP`：无需改 `config.json` 即可覆盖引擎镜像（`engine=image,engine2=image2`）。
- `ENGINE_SEMGREP_IMAGE`、`ENGINE_TRIVY_IMAGE`：对单个引擎的快捷覆盖。
- `HTTP_PROXY`、`HTTPS_PROXY`、`NO_PROXY`：当 `trivy.inheritProxyEnv=true` 时会转发到 Trivy 容器。

### 命令行参数

- `-config`：`config.json` 路径。
- `-server`：覆盖 `config.json.server`。
- `-server-name`：覆盖 `config.json.serverName`。
- `-ca-cert`：覆盖 `config.json.caCertPath`。
- `-token`：覆盖 `config.json.token` / `EXECUTOR_TOKEN`。
- `-insecure`：覆盖 `config.json.insecure`。

## 启动

```bash
go test ./...
go build -o executor-agent .

cp config.temp config.json
# 编辑 config.json（server/serverName/caCertPath/token）
./executor-agent -config ./config.json
```

如果你希望把 token 放在 `.env` 中：

```bash
cp .env.example .env
set -a; . ./.env; set +a
./executor-agent -config ./config.json
```

## 引擎镜像解析

当任务 payload 未显式指定镜像时，执行机会按配置的引擎注册表解析镜像。缺失引擎会导致任务被拒绝。执行机会优先使用本机已存在的镜像（Docker `inspect`），仅在镜像不存在时才拉取。
