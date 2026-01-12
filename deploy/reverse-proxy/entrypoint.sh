#!/bin/sh
set -eu

: "${LISTEN_PORT:=80}"

envsubst '${LISTEN_PORT}' < /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf

exec "$@"

