#!/usr/bin/env bash
# Loads the local demo data (P1.9) into a running Compose stack. Dev only: nothing here runs in an
# image, a Flyway migration or Kubernetes.
#
#   docker compose -f docker-compose.microservices.yml --profile app up -d --build
#   scripts/seed-dev.sh
#
# Workforce is the only database written directly (services/workforce-service/seed/dev-seed.sql).
# Its outbox relay publishes workforce.employee.created.v1 for every seeded employee, and identity,
# scheduling and leave build their copies through their own consumers. So the consumers' queues must
# exist first (they are declared when those services start), otherwise the events are dropped.
#
# Passwords (optional): with BOOTSTRAP_ADMIN_EMAIL=admin@shift-happens.example and
# BOOTSTRAP_ADMIN_PASSWORD in .env, identity gives the seeded Administrator that password. If
# SEED_DEMO_PASSWORD is also set (environment or .env, min. 8 characters), this script then logs in as
# that admin through the gateway and sets SEED_DEMO_PASSWORD on every other active demo account with
# PUT /api/v1/auth/accounts/{id}/password, so the real peppered bcrypt hashing is used. No password
# is stored in this repository.
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.microservices.yml)
SEED_FILE=services/workforce-service/seed/dev-seed.sql
GATEWAY_URL="${GATEWAY_URL:-https://localhost:8443}"
# Queues bound to workforce.employee.*: required ones must exist, the others only warn.
REQUIRED_QUEUES=(identity.accounts)
OPTIONAL_QUEUES=(scheduling.command scheduling.projection leave.command leave.projection)
# Active seeded accounts other than the Administrator (employee ids 2-11 and 13; 12 is INACTIVE).
DEMO_ACCOUNT_IDS=(2 3 4 5 6 7 8 9 10 11 13)

# Reads KEY from the environment, falling back to .env (not sourced: .env can hold shell-unsafe values).
setting() {
    local key="$1" value="${!1:-}"
    if [ -z "$value" ] && [ -f .env ]; then
        value="$(grep -E "^${key}=" .env | tail -n 1 | cut -d= -f2- || true)"
    fi
    printf '%s' "$value"
}

json_string() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    printf '"%s"' "$s"
}

echo "seed-dev: checking that workforce-service has migrated workforce-db"
schema_ready="$("${COMPOSE[@]}" exec -T workforce-db sh -c 'psql -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    <<<"SELECT to_regclass('public.employee_terms_refresh') IS NOT NULL;")"
if [ "$schema_ready" != "t" ]; then
    echo "seed-dev: workforce-db has no schema yet; start workforce-service first (--profile app)" >&2
    exit 1
fi

echo "seed-dev: checking the consumer queues"
queues="$("${COMPOSE[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p shift-happens name)"
for queue in "${REQUIRED_QUEUES[@]}"; do
    if ! grep -qx "$queue" <<<"$queues"; then
        echo "seed-dev: queue $queue does not exist; start the consuming service first, or its copy misses the seed" >&2
        exit 1
    fi
done
for queue in "${OPTIONAL_QUEUES[@]}"; do
    if ! grep -qx "$queue" <<<"$queues"; then
        echo "seed-dev: warning: queue $queue does not exist; that service will not get the demo employees" >&2
    fi
done

echo "seed-dev: loading $SEED_FILE"
"${COMPOSE[@]}" exec -T workforce-db sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < "$SEED_FILE"

demo_password="$(setting SEED_DEMO_PASSWORD)"
if [ -z "$demo_password" ]; then
    echo "seed-dev: done. SEED_DEMO_PASSWORD is not set, so only the bootstrap admin (if configured) can log in."
    exit 0
fi
admin_email="$(setting BOOTSTRAP_ADMIN_EMAIL)"
admin_password="$(setting BOOTSTRAP_ADMIN_PASSWORD)"
if [ -z "$admin_email" ] || [ -z "$admin_password" ]; then
    echo "seed-dev: SEED_DEMO_PASSWORD needs BOOTSTRAP_ADMIN_EMAIL and BOOTSTRAP_ADMIN_PASSWORD to set passwords" >&2
    exit 1
fi

# The admin account arrives asynchronously (outbox relay -> RabbitMQ -> identity), so retry the login.
echo "seed-dev: waiting for the admin login to work"
token=""
for _ in $(seq 1 30); do
    # Bodies go through stdin, so passwords never show up in the process list.
    response="$(printf '{"email":%s,"password":%s}' "$(json_string "$admin_email")" "$(json_string "$admin_password")" \
        | curl -ksS -X POST "$GATEWAY_URL/api/v1/auth/login" -H 'Content-Type: application/json' \
            --data-binary @- -w '\n%{http_code}' || true)"
    if [ "$(tail -n 1 <<<"$response")" = "200" ]; then
        token="$(head -n 1 <<<"$response" | sed -E 's/.*"token":"([^"]+)".*/\1/')"
        break
    fi
    sleep 2
done
if [ -z "$token" ]; then
    echo "seed-dev: the admin could not log in at $GATEWAY_URL; is BOOTSTRAP_ADMIN_EMAIL the seeded admin?" >&2
    exit 1
fi

body="{\"password\":$(json_string "$demo_password")}"
for id in "${DEMO_ACCOUNT_IDS[@]}"; do
    # The account can still be on its way from workforce: 404 is retried.
    for attempt in $(seq 1 15); do
        status="$(printf '%s' "$body" | curl -ksS -o /dev/null -w '%{http_code}' -X PUT \
            "$GATEWAY_URL/api/v1/auth/accounts/$id/password" -H "Authorization: Bearer $token" \
            -H 'Content-Type: application/json' --data-binary @- || true)"
        [ "$status" = "204" ] && break
        if [ "$status" != "404" ] || [ "$attempt" = 15 ]; then
            echo "seed-dev: setting the password of employee $id failed with HTTP $status" >&2
            exit 1
        fi
        sleep 2
    done
done
echo "seed-dev: done. Demo accounts can log in with SEED_DEMO_PASSWORD (see services/workforce-service/README.md)."
