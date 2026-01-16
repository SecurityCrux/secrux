#!/bin/bash
set -euo pipefail

log() {
  printf '[semgrep-entrypoint] %s\n' "$1"
}

# Perform a non-interactive Semgrep login when a token is provided.
if [ -n "${SEMGREP_APP_TOKEN:-}" ]; then
  log "Authenticating with provided SEMGREP_APP_TOKEN"
  semgrep login --token "${SEMGREP_APP_TOKEN}" --force >/tmp/semgrep-login.log 2>&1 \
    || log "Semgrep login failed, continuing without persisted credentials"
fi

DEFAULT_COMMAND="${SEMGREP_DEFAULT_COMMAND:-ci}"

if [ "$#" -eq 0 ]; then
  set -- "$DEFAULT_COMMAND"
fi

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

if [ -n "${SEMGREP_CONFIG:-}" ] && ! has_flag "--config" "$@"; then
  log "Injecting --config ${SEMGREP_CONFIG}"
  set -- "$@" "--config" "${SEMGREP_CONFIG}"
fi

if [ -n "${SEMGREP_FORMAT:-}" ] && ! has_flag "--format" "$@"; then
  log "Injecting --format ${SEMGREP_FORMAT}"
  set -- "$@" "--format" "${SEMGREP_FORMAT}"
fi

if [ -n "${SEMGREP_OUTPUT_FILE:-}" ] && ! has_flag "--output" "$@"; then
  log "Injecting --output ${SEMGREP_OUTPUT_FILE}"
  set -- "$@" "--output" "${SEMGREP_OUTPUT_FILE}"
fi

if [ "${SEMGREP_USE_PRO:-false}" = "true" ] && ! has_flag "--pro" "$@"; then
  log "Injecting --pro flag"
  set -- "$@" "--pro"
fi

if [ "${SEMGREP_ENABLE_SARIF:-false}" = "true" ] && ! has_flag "--sarif" "$@"; then
  log "Injecting --sarif flag via SEMGREP_ENABLE_SARIF"
  set -- "$@" "--sarif"
fi

run_with_logging() {
  if [ -z "${SEMGREP_LOG_FILE:-}" ]; then
    log "Executing: semgrep $*"
    exec semgrep "$@"
  fi

  TMP_LOG="$(mktemp)"
  trap 'rm -f "$TMP_LOG"' EXIT
  log "Executing with log capture: semgrep $*"
  set +e
  semgrep "$@" 2>&1 | tee "$TMP_LOG"
  status=${PIPESTATUS[0]}
  set -e
  python - "$TMP_LOG" "${SEMGREP_LOG_FILE}" <<'PY'
import json, sys, pathlib
tmp_log = pathlib.Path(sys.argv[1])
dest = pathlib.Path(sys.argv[2])
dest.parent.mkdir(parents=True, exist_ok=True)
lines = tmp_log.read_text(encoding="utf-8", errors="ignore").splitlines()
payload = {"log": lines}
dest.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
PY
  exit $status
}

run_with_logging "$@"

