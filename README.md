# Secrux Engines

[中文说明](README.zh-CN.md)

`secrux-engines` contains the build definitions and helper scripts for scanner engine images (Semgrep/Trivy).
These images are launched by `executor-agent` as part of task execution.

## Requirements

- Docker (engine images are Docker images)

## Build engine images (Docker Compose)

```bash
cd secrux-engines
cp .env.example .env
docker compose build
```

Or use the one-click helper script:

```bash
cd secrux-engines
./build-local-engines.sh
```

By default, it produces:

- `secrux-semgrep-engine:latest`
- `secrux-trivy-engine:latest`

## Configuration reference

### Build-time (compose `.env`)

Defined in `secrux-engines/.env.example`:

- `SEMGREP_IMAGE`: Upstream base image used to build the Semgrep engine.
- `TRIVY_IMAGE`: Upstream base image used to build the Trivy engine.
- `SECRUX_SEMGREP_ENGINE_IMAGE`: Output image tag for the built Semgrep engine.
- `SECRUX_TRIVY_ENGINE_IMAGE`: Output image tag for the built Trivy engine.

### Runtime env (engine containers)

These are consumed by the entrypoint scripts and are typically provided by `executor-agent` via task env:

**Semgrep (`run-semgrep.sh`)**
- `SEMGREP_APP_TOKEN`: If set, runs `semgrep login --token ...` (non-interactive).
- `SEMGREP_DEFAULT_COMMAND`: Default command when no args are provided (default `ci`).
- `SEMGREP_CONFIG`: Injects `--config` when missing.
- `SEMGREP_FORMAT`: Injects `--format` when missing.
- `SEMGREP_OUTPUT_FILE`: Injects `--output` when missing.
- `SEMGREP_USE_PRO`: When `true`, injects `--pro`.
- `SEMGREP_ENABLE_SARIF`: When `true`, injects `--sarif`.
- `SEMGREP_LOG_FILE`: When set, captures output and writes a JSON log file.

**Trivy (`run-trivy.sh`)**
- `TRIVY_CACHE_DIR`: Creates the directory and injects `--cache-dir` when missing.
- `TRIVY_NO_PROGRESS`: When `true`, injects `--no-progress` if supported by the Trivy version.

## Using in executors

Configure your executor to use the built images (example snippet for `config.json`):

```json
{
  "engineImages": {
    "semgrep": "secrux-semgrep-engine:latest",
    "trivy": "secrux-trivy-engine:latest"
  }
}
```

## Scripts

- `run-semgrep.sh`: wrapper entrypoint used by the Semgrep engine image
- `run-trivy.sh`: wrapper entrypoint used by the Trivy engine image
