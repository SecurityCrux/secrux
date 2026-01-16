#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="${SECRUX_COMPOSE_FILE:-${root_dir}/docker/docker-compose.yml}"

services=(ai-service secrux-server secrux-console)
failed=()

for service in "${services[@]}"; do
  if ! docker compose -f "${compose_file}" pull "${service}"; then
    failed+=("${service}")
  fi
done

if ((${#failed[@]})); then
  echo "Pull failed for: ${failed[*]}. Building locally."
  docker compose -f "${compose_file}" build "${failed[@]}"
fi

docker compose -f "${compose_file}" up -d --no-build
docker compose -f "${compose_file}" ps
