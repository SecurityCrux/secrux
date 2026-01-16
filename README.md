# Secrux Executor Agent

[中文说明](README.zh-CN.md)

The agent connects to the Secrux control plane over the Executor Gateway (TLS),
reports heartbeat metrics, receives `task_assign` messages, and launches Docker
containers for each engine.

This is a **Go binary** (not a Docker container). The host running the agent must have:

- Docker installed and the daemon running (to launch engine containers)
- Network access to:
  - Executor Gateway (default `:5155`)
  - Secrux Server API base URL (for download/upload endpoints)

## Configuration

1. **Config file (recommended)**  
   Copy `config.temp` to `config.json`, fill your values, then run with `-config ./config.json`.
   Example (JSON):

   ```jsonc
	   {
	     "server": "gateway.secrux.internal:5155",
	     "serverName": "gateway.secrux.internal",
	     "caCertPath": "../certs/executor-gateway/gateway-ca.crt",
	     "token": "EXECUTOR_TOKEN",
	     "insecure": false,
	     "engineImages": {
	       "semgrep": "ghcr.io/secrux/semgrep:latest",
	       "trivy": "ghcr.io/secrux/trivy:latest",
	       "grype": "ghcr.io/secrux/grype:1.0.2",
	       "dependency-check": "registry.local/dc:2024.10"
	     },
	     "trivy": {
	       "sanitizePomRepositories": true,
	       "bannedMavenRepoHosts": [
	         "dl.bintray.com",
	         "jcenter.bintray.com",
	         "repo.bintray.com"
	       ],
	       "filesystemCopyMode": "auto",
	       "timeoutSec": 1200,
	       "mavenRepositoryPath": "~/.m2/repository",
	       "mavenSettingsPath": "~/.m2/settings.xml",
	       "cacheHostPath": "~/.cache/secrux/trivy",
	       "inheritProxyEnv": true
	     }
	   }
	   ```

   Note: `token` is a password. Rotate it if leaked.

   Trivy note: `sanitizePomRepositories` removes dead Maven repositories from `pom.xml`
   (e.g. Bintray) to avoid Trivy timing out during dependency resolution. `filesystemCopyMode`
   controls whether the agent copies `source.filesystem.path` into a temp dir before rewriting POMs
   (`auto` = copy only for local filesystem sources).

   To reduce network dependence, the agent can mount the host Maven cache (`mavenRepositoryPath`)
   and Maven settings (`mavenSettingsPath`) into the Trivy container, plus a persistent Trivy cache
   dir (`cacheHostPath`) for vulnerability DB reuse.
   Paths support `~` expansion.

2. **Flags & environment variables**  
   - `-server`, `-server-name`, `-ca-cert`, `-token`, `-insecure` override the config file.  
   - `EXECUTOR_TOKEN` seeds the token if the flag is omitted (useful with `.env` / systemd `EnvironmentFile`).  
   - `ENGINE_IMAGE_MAP` (`engine=image,...`) and `ENGINE_SEMGREP_IMAGE`
     override individual entries after the file is loaded.

3. **Precedence**  
   - Connection/TLS: defaults → `config.json` → flags  
   - Token: `-token` → `config.json.token` → `EXECUTOR_TOKEN`  
   - Engine images: `config.json.engineImages` → `ENGINE_IMAGE_MAP` → `ENGINE_SEMGREP_IMAGE`/`ENGINE_TRIVY_IMAGE` → defaults  
   - Trivy options: defaults → `config.json.trivy`

## Gateway TLS certificates

When `insecure=false`, the agent verifies that the gateway certificate matches `serverName` (SAN/hostname verification).

For local quickstart, you can generate a certificate pair (with SANs) and make the server use it:

```bash
# from repo root
./gen-executor-gateway-certs.sh
docker compose up -d --force-recreate secrux-server
```

Then set `caCertPath` to the generated CA (recommended): `../certs/executor-gateway/gateway-ca.crt`
and ensure `serverName` matches one of the SANs (defaults include `gateway.secrux.internal` and `localhost`).

## Configuration reference

### `config.json`

- `server` (string): Executor Gateway address, in `host:port` format (TCP over TLS).
- `serverName` (string): TLS server name override (SNI + hostname verification). Use this when connecting by IP/alias but the certificate is issued for a DNS name.
- `caCertPath` (string): Path to a CA certificate bundle (PEM). When set, it is appended to the system trust store for verifying the gateway certificate.
- `token` (string): Provisioned executor token (treat as a password).
- `insecure` (bool): **Dev only**. Skips TLS verification (`InsecureSkipVerify=true`). The connection is still TLS-encrypted but the gateway identity is not verified (MITM risk). When `true`, `caCertPath` is ignored.
- `engineImages` (object): Map of `engine -> docker image`. Used when a task payload does not include an explicit `image`. Keys are case-insensitive.
- `trivy` (object): Trivy-specific execution options:
  - `sanitizePomRepositories` (bool): Removes banned Maven repository URLs from `pom.xml` before scanning to reduce dependency resolution failures/timeouts.
  - `bannedMavenRepoHosts` (string[]): Hosts to remove from Maven repository URLs (defaults include Bintray/JCenter).
  - `filesystemCopyMode` (`auto` | `always` | `never`): Whether to copy the scan source dir before sanitizing `pom.xml`.
  - `timeoutSec` (number): Overrides the Trivy task timeout in seconds when set (>0). Defaults to the task timeout or 20 minutes.
  - `mavenRepositoryPath` (string): Host path to mount into the Trivy container Maven cache (`/root/.m2/repository`, read-only).
  - `mavenSettingsPath` (string): Host path to mount as Maven `settings.xml` (`/root/.m2/settings.xml`, read-only).
  - `cacheHostPath` (string): Host path for Trivy cache persistence (mounted to `/tmp/trivy-cache`).
  - `inheritProxyEnv` (bool): Passes `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` from the host into the Trivy container if not already present in the task env.

### Environment variables

- `EXECUTOR_TOKEN`: Seeds `-token` when the flag is omitted.
- `ENGINE_IMAGE_MAP`: Overrides engine images without editing `config.json` (`engine=image,engine2=image2`).
- `ENGINE_SEMGREP_IMAGE`, `ENGINE_TRIVY_IMAGE`: Convenience overrides for individual engines.
- `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY`: Forwarded to Trivy containers when `trivy.inheritProxyEnv=true`.

### Flags

- `-config`: Path to `config.json`.
- `-server`: Override `config.json.server`.
- `-server-name`: Override `config.json.serverName`.
- `-ca-cert`: Override `config.json.caCertPath`.
- `-token`: Override `config.json.token` / `EXECUTOR_TOKEN`.
- `-insecure`: Override `config.json.insecure`.

## Running

```bash
go test ./...
go build -o executor-agent .

cp config.temp config.json
# edit config.json (server/serverName/caCertPath/token)
./executor-agent -config ./config.json
```

If you prefer keeping the token outside the config file:

```bash
cp .env.example .env
set -a; . ./.env; set +a
./executor-agent -config ./config.json
```

## Engine registry

When a task payload omits an explicit Docker image, the agent resolves it from
the combined registry described above. If an engine is missing, the task is
rejected (Semgrep defaults to `secrux-semgrep-engine:latest`). Use the config
file to define the baseline mapping per executor host and environment
variables for temporary overrides.

The agent prefers a locally available image (Docker `inspect`) and only pulls
from a registry when the image is missing locally.
