#!/bin/sh
set -eu

log() {
  printf '[trivy-entrypoint] %s\n' "$*" >&2
}

has_flag() {
  flag_to_find="$1"
  shift
  for existing in "$@"; do
    if [ "$existing" = "$flag_to_find" ]; then
      return 0
    fi
  done
  return 1
}

if [ -n "${TRIVY_CACHE_DIR:-}" ]; then
  mkdir -p "${TRIVY_CACHE_DIR}" 2>/dev/null || true
  if ! has_flag "--cache-dir" "$@"; then
    set -- "--cache-dir" "${TRIVY_CACHE_DIR}" "$@"
  fi
fi

if [ "${TRIVY_NO_PROGRESS:-false}" = "true" ] && ! has_flag "--no-progress" "$@"; then
  if trivy --help 2>&1 | grep -q -- "--no-progress"; then
    set -- "--no-progress" "$@"
  else
    log "Flag --no-progress not supported by this Trivy version; skipping"
  fi
fi

if [ "$#" -eq 0 ]; then
  log "No args provided; showing help"
  exec trivy --help
fi

log "Executing: trivy $*"
exec trivy "$@"
