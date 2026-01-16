# Secrux Engines（扫描引擎）

[English](README.md)

`secrux-engines` 提供扫描引擎镜像（Semgrep/Trivy）的构建定义与辅助脚本。
这些镜像由 `executor-agent` 在任务执行时拉起运行。

## 环境要求

- Docker（引擎以 Docker 镜像形式运行）

## 构建引擎镜像（Docker Compose）

```bash
cd secrux-engines
cp .env.example .env
docker compose build
```

或使用一键脚本：

```bash
cd secrux-engines
./build-local-engines.sh
```

默认产物：

- `secrux-semgrep-engine:latest`
- `secrux-trivy-engine:latest`

## 配置项解析

### 构建期（compose `.env`）

定义在 `secrux-engines/.env.example`：

- `SEMGREP_IMAGE`：用于构建 Semgrep 引擎的上游基础镜像。
- `TRIVY_IMAGE`：用于构建 Trivy 引擎的上游基础镜像。
- `SECRUX_SEMGREP_ENGINE_IMAGE`：Semgrep 引擎构建产物镜像名/Tag。
- `SECRUX_TRIVY_ENGINE_IMAGE`：Trivy 引擎构建产物镜像名/Tag。

### 运行期环境变量（引擎容器）

以下变量由入口脚本读取，通常由 `executor-agent` 通过任务 env 传入：

**Semgrep（`run-semgrep.sh`）**
- `SEMGREP_APP_TOKEN`：存在时会执行 `semgrep login --token ...`（非交互）。
- `SEMGREP_DEFAULT_COMMAND`：无参数时的默认子命令（默认 `ci`）。
- `SEMGREP_CONFIG`：当参数中缺少 `--config` 时注入。
- `SEMGREP_FORMAT`：当参数中缺少 `--format` 时注入。
- `SEMGREP_OUTPUT_FILE`：当参数中缺少 `--output` 时注入。
- `SEMGREP_USE_PRO`：为 `true` 时注入 `--pro`。
- `SEMGREP_ENABLE_SARIF`：为 `true` 时注入 `--sarif`。
- `SEMGREP_LOG_FILE`：存在时会捕获输出并写入 JSON 格式日志文件。

**Trivy（`run-trivy.sh`）**
- `TRIVY_CACHE_DIR`：创建目录并在缺少 `--cache-dir` 时注入该参数。
- `TRIVY_NO_PROGRESS`：为 `true` 时，如果 Trivy 支持则注入 `--no-progress`。

## 在执行机中使用

在执行机配置中指定镜像（`config.json` 示例片段）：

```json
{
  "engineImages": {
    "semgrep": "secrux-semgrep-engine:latest",
    "trivy": "secrux-trivy-engine:latest"
  }
}
```

## 脚本

- `run-semgrep.sh`：Semgrep 引擎镜像的入口包装脚本
- `run-trivy.sh`：Trivy 引擎镜像的入口包装脚本
