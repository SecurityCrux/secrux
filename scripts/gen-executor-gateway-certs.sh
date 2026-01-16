#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Generate a TLS certificate pair for Secrux Executor Gateway (with SANs).

Outputs (default): ./certs/executor-gateway/
  - gateway.crt        (server certificate chain: leaf + CA)
  - gateway.key        (server private key)
  - gateway-ca.crt     (CA certificate)
  - gateway-ca.key     (CA private key)

Usage:
  ./scripts/gen-executor-gateway-certs.sh [options]

Options:
  --dns <name>     Add a DNS SAN (repeatable). Default: gateway.secrux.internal, localhost
  --ip <ip>        Add an IP SAN (repeatable). Default: 127.0.0.1
  --days <days>    Certificate validity in days. Default: 3650
  --out-dir <dir>  Output directory. Default: certs/executor-gateway
  --force          Overwrite existing files.
  -h, --help       Show this help.

Examples:
  ./scripts/gen-executor-gateway-certs.sh
  ./scripts/gen-executor-gateway-certs.sh --dns gateway.example.com --ip 10.0.0.10
EOF
}

OUT_DIR="certs/executor-gateway"
DAYS="3650"
FORCE="false"
DNS_NAMES=()
IP_ADDRESSES=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dns)
      if [[ $# -lt 2 ]]; then
        echo "--dns requires a value" >&2
        exit 2
      fi
      DNS_NAMES+=("$2")
      shift 2
      ;;
    --ip)
      if [[ $# -lt 2 ]]; then
        echo "--ip requires a value" >&2
        exit 2
      fi
      IP_ADDRESSES+=("$2")
      shift 2
      ;;
    --days)
      if [[ $# -lt 2 ]]; then
        echo "--days requires a value" >&2
        exit 2
      fi
      DAYS="$2"
      shift 2
      ;;
    --out-dir)
      if [[ $# -lt 2 ]]; then
        echo "--out-dir requires a value" >&2
        exit 2
      fi
      OUT_DIR="$2"
      shift 2
      ;;
    --force)
      FORCE="true"
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

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl is required but was not found in PATH" >&2
  exit 1
fi

if [[ ${#DNS_NAMES[@]} -eq 0 ]]; then
  DNS_NAMES=("gateway.secrux.internal" "localhost")
fi
if [[ ${#IP_ADDRESSES[@]} -eq 0 ]]; then
  IP_ADDRESSES=("127.0.0.1")
fi

CN="${DNS_NAMES[0]}"

mkdir -p "$OUT_DIR"

CA_KEY="$OUT_DIR/gateway-ca.key"
CA_CERT="$OUT_DIR/gateway-ca.crt"
SERVER_KEY="$OUT_DIR/gateway.key"
SERVER_CERT_LEAF="$OUT_DIR/gateway-leaf.crt"
SERVER_CERT_CHAIN="$OUT_DIR/gateway.crt"
CSR="$OUT_DIR/gateway.csr"
SERIAL="$OUT_DIR/gateway-ca.srl"

if [[ -f "$CA_KEY" || -f "$CA_CERT" || -f "$SERVER_KEY" || -f "$SERVER_CERT_CHAIN" ]]; then
  if [[ "$FORCE" != "true" ]]; then
    echo "Certificates already exist in '$OUT_DIR' (use --force to overwrite)." >&2
    exit 0
  fi
  rm -f "$CA_KEY" "$CA_CERT" "$SERVER_KEY" "$SERVER_CERT_LEAF" "$SERVER_CERT_CHAIN" "$CSR" "$SERIAL"
fi

CA_CONF="$(mktemp)"
SERVER_CONF="$(mktemp)"
trap 'rm -f "$CA_CONF" "$SERVER_CONF"' EXIT

cat > "$CA_CONF" <<'EOF'
[ req ]
distinguished_name = dn
x509_extensions = v3_ca
prompt = no

[ dn ]
CN = Secrux Executor Gateway CA

[ v3_ca ]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
EOF

cat > "$SERVER_CONF" <<EOF
[ req ]
distinguished_name = dn
req_extensions = v3_req
prompt = no

[ dn ]
CN = $CN

[ v3_req ]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[ alt_names ]
EOF

dnsIndex=1
for dns in "${DNS_NAMES[@]}"; do
  dnsTrimmed="$(echo "$dns" | awk '{$1=$1;print}')"
  if [[ -n "$dnsTrimmed" ]]; then
    echo "DNS.$dnsIndex = $dnsTrimmed" >> "$SERVER_CONF"
    dnsIndex=$((dnsIndex + 1))
  fi
done

ipIndex=1
for ip in "${IP_ADDRESSES[@]}"; do
  ipTrimmed="$(echo "$ip" | awk '{$1=$1;print}')"
  if [[ -n "$ipTrimmed" ]]; then
    echo "IP.$ipIndex = $ipTrimmed" >> "$SERVER_CONF"
    ipIndex=$((ipIndex + 1))
  fi
done

openssl genrsa -out "$CA_KEY" 2048 >/dev/null 2>&1
openssl req -x509 -new -nodes -key "$CA_KEY" -sha256 -days "$DAYS" -out "$CA_CERT" -config "$CA_CONF" >/dev/null 2>&1

openssl genrsa -out "$SERVER_KEY" 2048 >/dev/null 2>&1
openssl req -new -key "$SERVER_KEY" -out "$CSR" -config "$SERVER_CONF" >/dev/null 2>&1
openssl x509 -req -in "$CSR" -CA "$CA_CERT" -CAkey "$CA_KEY" -CAcreateserial -out "$SERVER_CERT_LEAF" -days "$DAYS" -sha256 -extfile "$SERVER_CONF" -extensions v3_req >/dev/null 2>&1

cat "$SERVER_CERT_LEAF" "$CA_CERT" > "$SERVER_CERT_CHAIN"

chmod 600 "$CA_KEY" "$SERVER_KEY"
chmod 644 "$CA_CERT" "$SERVER_CERT_LEAF" "$SERVER_CERT_CHAIN"

echo "Generated Executor Gateway certificates:"
echo "  - $SERVER_CERT_CHAIN"
echo "  - $SERVER_KEY"
echo "  - $CA_CERT"
echo
echo "Next steps:"
echo "  - Restart server: docker compose -f docker/docker-compose.yml up -d --force-recreate secrux-server"
echo "  - Configure executors to trust the CA (recommended): caCertPath=$CA_CERT"
