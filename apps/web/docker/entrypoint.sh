#!/bin/sh
set -eu

CONFIG_PATH="/usr/share/nginx/html/env-config.js"

AUTH_MODE="${SECRUX_AUTH_MODE_UI:-${SECRUX_AUTH_MODE:-keycloak}}"

cat > "${CONFIG_PATH}" <<EOF
window.__SECRUX_CONFIG__ = {
  apiBaseUrl: "${SECRUX_API_BASE_URL:-http://localhost:8080}",
  authMode: "${AUTH_MODE}",
  oidc: {
    baseUrl: "${SECRUX_OIDC_BASE_URL:-http://localhost:8081}",
    realm: "${SECRUX_OIDC_REALM:-secrux}",
    clientId: "${SECRUX_OIDC_CLIENT_ID:-secrux-console}",
    scope: "${SECRUX_OIDC_SCOPE:-openid}",
  },
  appVersion: "${SECRUX_APP_VERSION:-dev}",
};
EOF

exec "$@"
