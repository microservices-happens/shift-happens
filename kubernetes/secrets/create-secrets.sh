#!/usr/bin/env bash
# Creates or updates the per-service Kubernetes Secrets from two git-ignored local files:
#
#   kubernetes/secrets/secrets.env          values (template: secrets.env.example)
#   kubernetes/secrets/jwt-private-key.pem  identity-service RS256 signing key (PKCS#8 PEM)
#
# Usage (from anywhere, against the current kubectl context):
#   kubernetes/secrets/create-secrets.sh --generate   # first time: create both files with random values, then apply
#   kubernetes/secrets/create-secrets.sh              # apply the existing files (idempotent; re-run after edits)
#   kubernetes/secrets/create-secrets.sh --dry-run    # validate the files and list secret names/keys; no cluster access
#
# Environment: NAMESPACE (default: the context's namespace), SECRETS_DIR (default: this folder).
# Running pods keep the old values until restarted: kubectl rollout restart deployment/<name>.
set -euo pipefail

SECRETS_DIR="${SECRETS_DIR:-$(cd "$(dirname "$0")" && pwd)}"
ENV_FILE="$SECRETS_DIR/secrets.env"
EXAMPLE_FILE="$(cd "$(dirname "$0")" && pwd)/secrets.env.example"
KEY_FILE="$SECRETS_DIR/jwt-private-key.pem"

generate=false
dry_run=false
for arg in "$@"; do
  case "$arg" in
    --generate) generate=true ;;
    --dry-run) dry_run=true ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

umask 077

if $generate; then
  if [ -e "$ENV_FILE" ]; then
    echo "Keeping existing $ENV_FILE"
  else
    while IFS= read -r line || [ -n "$line" ]; do
      if [[ "$line" == *=CHANGE_ME ]]; then
        echo "${line%%=*}=$(openssl rand -hex 20)"
      else
        echo "$line"
      fi
    done < "$EXAMPLE_FILE" > "$ENV_FILE"
    echo "Wrote $ENV_FILE with random values"
  fi
  if [ -e "$KEY_FILE" ]; then
    echo "Keeping existing $KEY_FILE"
  else
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY_FILE" 2>/dev/null
    echo "Wrote $KEY_FILE"
  fi
fi

[ -f "$ENV_FILE" ] || { echo "Missing $ENV_FILE. Copy secrets.env.example or run with --generate." >&2; exit 1; }
[ -f "$KEY_FILE" ] || { echo "Missing $KEY_FILE. Run with --generate or create a PKCS#8 PEM key." >&2; exit 1; }
grep -q "BEGIN PRIVATE KEY" "$KEY_FILE" || { echo "$KEY_FILE is not a PKCS#8 PEM key (expected BEGIN PRIVATE KEY)." >&2; exit 1; }

# value KEY -> prints the value from secrets.env (last occurrence wins; empty if absent).
value() {
  local line result=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      "$1="*) result="${line#*=}" ;;
    esac
  done < "$ENV_FILE"
  printf '%s' "$result"
}

# Add the Grafana password when upgrading an existing secrets.env.
grafana_password="$(value GRAFANA_ADMIN_PASSWORD)"
if $generate && { [ -z "$grafana_password" ] || [ "$grafana_password" = "CHANGE_ME" ]; }; then
  grafana_password="$(openssl rand -hex 24)"
  printf '\nGRAFANA_ADMIN_PASSWORD=%s\n' "$grafana_password" >> "$ENV_FILE"
fi

required="RABBITMQ_PASSWORD IDENTITY_DB_PASSWORD PASSWORD_PEPPER WORKFORCE_DB_PASSWORD
  SCHEDULING_DB_PASSWORD SCHEDULING_MONGO_PASSWORD LEAVE_DB_PASSWORD LEAVE_MONGO_PASSWORD
  NOTIFICATION_DB_PASSWORD AUDIT_DB_PASSWORD AI_DB_PASSWORD GRAFANA_ADMIN_PASSWORD"
missing=""
for key in $required; do
  v="$(value "$key")"
  if [ -z "$v" ] || [ "$v" = "CHANGE_ME" ]; then missing="$missing $key"; fi
done
[ -z "$missing" ] || { echo "Set these in $ENV_FILE:$missing" >&2; exit 1; }

ns_args=()
[ -n "${NAMESPACE:-}" ] && ns_args=(--namespace "$NAMESPACE")

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# secret NAME TARGET=SOURCE... [--file KEY=PATH]
# TARGET is the key inside the Secret (the container's env var name), SOURCE the secrets.env name.
secret() {
  local name="$1"; shift
  # One file per key (exact value, no trailing newline), so values never show up in `ps`.
  local dir="$work/$name" extra=() keys=""
  mkdir "$dir"
  while [ $# -gt 0 ]; do
    if [ "$1" = "--file" ]; then
      extra+=(--from-file="$2"); keys="$keys${2%%=*} "; shift 2; continue
    fi
    printf '%s' "$(value "${1#*=}")" > "$dir/${1%%=*}"
    keys="$keys${1%%=*} "
    shift
  done
  local cmd=(kubectl create secret generic "$name" "${ns_args[@]+"${ns_args[@]}"}"
    --from-file="$dir" "${extra[@]+"${extra[@]}"}" --dry-run=client -o yaml)
  if $dry_run; then
    "${cmd[@]}" > "$work/$name.yaml"
    printf '%-30s %s\n' "$name" "$keys"
  else
    "${cmd[@]}" | kubectl apply "${ns_args[@]+"${ns_args[@]}"}" -f -
  fi
}

secret rabbitmq-secrets RABBITMQ_DEFAULT_PASS=RABBITMQ_PASSWORD
secret identity-service-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD DB_PASSWORD=IDENTITY_DB_PASSWORD \
  PASSWORD_PEPPER=PASSWORD_PEPPER BOOTSTRAP_ADMIN_EMAIL=BOOTSTRAP_ADMIN_EMAIL \
  BOOTSTRAP_ADMIN_PASSWORD=BOOTSTRAP_ADMIN_PASSWORD OIDC_GOOGLE_CLIENT_SECRET=OIDC_GOOGLE_CLIENT_SECRET \
  OIDC_GITHUB_CLIENT_SECRET=OIDC_GITHUB_CLIENT_SECRET --file "jwt-private-key.pem=$KEY_FILE"
secret workforce-service-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD DB_PASSWORD=WORKFORCE_DB_PASSWORD
secret scheduling-command-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD DB_PASSWORD=SCHEDULING_DB_PASSWORD
secret scheduling-query-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD MONGO_PASSWORD=SCHEDULING_MONGO_PASSWORD
secret leave-command-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD DB_PASSWORD=LEAVE_DB_PASSWORD
secret leave-query-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD MONGO_PASSWORD=LEAVE_MONGO_PASSWORD
secret notification-service-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD DB_PASSWORD=NOTIFICATION_DB_PASSWORD
secret audit-service-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD DB_PASSWORD=AUDIT_DB_PASSWORD
secret ai-service-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD DB_PASSWORD=AI_DB_PASSWORD
secret email-function-secrets RABBITMQ_PASSWORD=RABBITMQ_PASSWORD SMTP_USERNAME=SMTP_USERNAME \
  SMTP_PASSWORD=SMTP_PASSWORD

secret grafana-secrets GRAFANA_ADMIN_PASSWORD=GRAFANA_ADMIN_PASSWORD

if $dry_run; then echo "Dry run: nothing was sent to the cluster."; fi
