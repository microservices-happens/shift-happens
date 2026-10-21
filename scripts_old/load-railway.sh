#!/usr/bin/env bash
# Load the canonical Shift Happens schema + seed data into an EXTERNAL MySQL
# (e.g. Railway). It reads the ACTIVE (uncommented) DB_URL / DB_USERNAME /
# DB_PASSWORD from .env, so paste your Railway credentials there first and make
# sure the Railway block is the only uncommented DB block.
#
#   Usage:  ./scripts/load-railway.sh        (or: make load-railway)
#
# Loads docker/init/*.sql in the same order the local Docker container uses,
# through a throwaway mysql:8.0 client container (no local mysql client needed).
set -euo pipefail

cd "$(dirname "$0")/.."          # repo root
ENV_FILE=".env"
INIT_DIR="docker/init"
DB_NAME="shift_happens"          # the init scripts hardcode this database name

# Files in the exact order the docker entrypoint applies them.
# (Skips 09-create-app-user.sh / .txt — on Railway we connect as the DB owner.)
FILES=(
  01-schema.sql
  02-seed-data.sql
  03-alter-employees.sql
  04-user-roles.sql
  05-triggers.sql
  06-events.sql
  07-views.sql
  08-routines.sql
  09-seed-test-logins.sql
  10-db-users.sql
)

# Read a value from .env WITHOUT shell-evaluating it (URLs contain & and ?).
read_env() { grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-; }
DB_URL="$(read_env DB_URL)"
DB_USER="$(read_env DB_USERNAME)"
DB_PASS="$(read_env DB_PASSWORD)"

if [ -z "$DB_URL" ]; then
  echo "ERROR: no active DB_URL in $ENV_FILE — uncomment your Railway block first." >&2
  exit 1
fi

# Parse host:port out of  jdbc:mysql://HOST:PORT/db?params
tmp="${DB_URL#jdbc:mysql://}"
hostport="${tmp%%/*}"
DB_HOST="${hostport%%:*}"
DB_PORT="${hostport##*:}"
[ "$DB_PORT" = "$hostport" ] && DB_PORT=3306   # no :port in url -> default

if [ "$DB_HOST" = "localhost" ] || [ "$DB_HOST" = "127.0.0.1" ]; then
  echo "Refusing to run: active DB_URL points at $DB_HOST (your local container)." >&2
  echo "Uncomment the Railway block in $ENV_FILE first." >&2
  exit 1
fi

echo "About to load the FULL schema + seed data into:"
echo "    host: $DB_HOST"
echo "    port: $DB_PORT"
echo "    user: $DB_USER"
echo "    database: $DB_NAME"
echo
read -r -p "This overwrites that database's schema. Continue? [y/N] " ans
case "$ans" in y|Y) ;; *) echo "Aborted."; exit 0 ;; esac

# mysql client in a throwaway container; password via env to avoid CLI warning.
mysql_run() {  # extra args appended after client; SQL comes on stdin
  docker run --rm -i -e MYSQL_PWD="$DB_PASS" mysql:8.0 \
    mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$@"
}

echo "Creating database $DB_NAME (if needed)..."
echo "CREATE DATABASE IF NOT EXISTS $DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | mysql_run

for f in "${FILES[@]}"; do
  echo "Loading $f ..."
  mysql_run "$DB_NAME" < "$INIT_DIR/$f"
done

echo
echo "Done. Table count on Railway:"
echo "SELECT COUNT(*) AS tables FROM information_schema.tables WHERE table_schema='$DB_NAME';" | mysql_run
echo "Note: MySQL events won't auto-run until the event scheduler is ON for the instance."
