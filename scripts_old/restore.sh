#!/usr/bin/env bash
# Usage: bash scripts/restore.sh backups/<timestamp>
set -euo pipefail

BACKUP_DIR="${1:-}"
if [[ -z "$BACKUP_DIR" ]]; then
  echo "Usage: bash scripts/restore.sh backups/<timestamp>"
  echo ""
  echo "Available backups:"
  ls -1 backups/ 2>/dev/null || echo "  (none found)"
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Resolve to absolute path
BACKUP_DIR="$(cd "$BACKUP_DIR" && pwd)"

# Load env vars
set -a; source .env; set +a

RED='\033[0;31m'; GREEN='\033[0;32m'; BOLD='\033[1m'; NC='\033[0m'

log()  { echo -e "$1"; }
ok()   { log "${GREEN}  ✓ $1${NC}"; }
fail() { log "${RED}  ✗ $1${NC}"; exit 1; }

log "${BOLD}=== Shift Happens Restore ===${NC}"
log "    Source: $BACKUP_DIR"
log ""

# ── Validate backup contents ──────────────────────────────────
[[ -f "$BACKUP_DIR/mysql.sql"  ]] || fail "mysql.sql not found in backup"
[[ -d "$BACKUP_DIR/mongodb"    ]] || fail "mongodb/ directory not found in backup"
[[ -f "$BACKUP_DIR/neo4j.dump" ]] || fail "neo4j.dump not found in backup"

# ── MySQL ──────────────────────────────────────────────────────
log "[1/3] MySQL..."
docker exec shift-happens-db mysql \
  -u root -p"${MYSQL_ROOT_PASSWORD}" \
  -e "DROP DATABASE IF EXISTS \`${MYSQL_DATABASE}\`; CREATE DATABASE \`${MYSQL_DATABASE}\`;" 2>/dev/null

docker exec -i shift-happens-db mysql \
  -u root -p"${MYSQL_ROOT_PASSWORD}" \
  "${MYSQL_DATABASE}" < "$BACKUP_DIR/mysql.sql" 2>/dev/null

ROW_COUNT=$(docker exec shift-happens-db mysql \
  -u root -p"${MYSQL_ROOT_PASSWORD}" -sN \
  -e "SELECT COUNT(*) FROM \`${MYSQL_DATABASE}\`.employee;" 2>/dev/null)
ok "MySQL restored — $ROW_COUNT employee rows"

# ── MongoDB ────────────────────────────────────────────────────
log ""
log "[2/3] MongoDB..."
docker cp "$BACKUP_DIR/mongodb" shift-happens-mongo:/tmp/mongorestore_src 2>/dev/null

docker exec shift-happens-mongo mongorestore \
  --db shift_happens --drop /tmp/mongorestore_src --quiet 2>/dev/null

docker exec shift-happens-mongo rm -rf /tmp/mongorestore_src 2>/dev/null

DOC_COUNT=$(docker exec shift-happens-mongo mongosh shift_happens --quiet \
  --eval "db.employees.countDocuments()" 2>/dev/null | tail -1)
ok "MongoDB restored — $DOC_COUNT employee documents"

# ── Neo4j ──────────────────────────────────────────────────────
log ""
log "[3/3] Neo4j (requires brief stop)..."
docker compose stop neo4j 2>/dev/null

docker compose run --rm --no-deps \
  -v "$BACKUP_DIR:/backup" \
  --entrypoint neo4j-admin \
  neo4j \
  database load neo4j --from-path=/backup --overwrite-destination 2>/dev/null

docker compose start neo4j 2>/dev/null

log "    Waiting for Neo4j to be ready..."
for i in $(seq 1 12); do
  if docker exec shift-happens-neo4j cypher-shell \
      -u neo4j -p "${NEO4J_PASSWORD}" "RETURN 1;" >/dev/null 2>&1; then
    break
  fi
  [[ $i -eq 12 ]] && fail "Neo4j did not become ready in time"
  sleep 5
done

NODE_COUNT=$(docker exec shift-happens-neo4j cypher-shell \
  -u neo4j -p "${NEO4J_PASSWORD}" \
  "MATCH (n) RETURN count(n) AS count;" 2>/dev/null \
  | grep -oE '[0-9]+' | tail -1)
ok "Neo4j restored — $NODE_COUNT nodes"

# ── Summary ────────────────────────────────────────────────────
log ""
log "${GREEN}${BOLD}Restore complete.${NC}"
log "  MySQL     $ROW_COUNT employee rows"
log "  MongoDB   $DOC_COUNT employee documents"
log "  Neo4j     $NODE_COUNT nodes"
