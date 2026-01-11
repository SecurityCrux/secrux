#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build Secrux engine images locally (Semgrep + Trivy).

Usage:
  ./build-local-engines.sh [options]

Options:
  --pull        Always attempt to pull a newer base image.
  --no-cache    Do not use build cache.
  -h, --help    Show this help.

Notes:
  - This builds Docker images (not long-running containers).
  - Output image tags are controlled by `secrux-engines/.env`:
      SECRUX_SEMGREP_ENGINE_IMAGE
      SECRUX_TRIVY_ENGINE_IMAGE
EOF
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker is required but was not found in PATH" >&2
  exit 1
fi

COMPOSE=()
if docker compose version >/dev/null 2>&1; then
  COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE=(docker-compose)
else
  echo "docker compose (plugin) or docker-compose is required but was not found" >&2
  exit 1
fi

BUILD_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --pull)
      BUILD_ARGS+=("--pull")
      shift 1
      ;;
    --no-cache)
      BUILD_ARGS+=("--no-cache")
      shift 1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ ! -f ".env" ]]; then
  if [[ -f ".env.example" ]]; then
    cp .env.example .env
    echo "Created $(pwd)/.env from .env.example"
  else
    echo "Missing .env and .env.example" >&2
    exit 1
  fi
fi

"${COMPOSE[@]}" build "${BUILD_ARGS[@]}" semgrep-engine trivy-engine

SEMGREP_OUT="$(awk -F= '$1=="SECRUX_SEMGREP_ENGINE_IMAGE"{print substr($0,index($0,"=")+1)}' .env | tail -n1)"
TRIVY_OUT="$(awk -F= '$1=="SECRUX_TRIVY_ENGINE_IMAGE"{print substr($0,index($0,"=")+1)}' .env | tail -n1)"

echo
echo "Built engine images:"
echo "  - ${SEMGREP_OUT:-secrux-semgrep-engine:latest}"
echo "  - ${TRIVY_OUT:-secrux-trivy-engine:latest}"
