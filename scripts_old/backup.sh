#!/usr/bin/env bash
# Usage: bash scripts/backup.sh
# Dumps MySQL, MongoDB, and Neo4j to backups/<YYYYMMDD_HHMMSS>/
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Load env vars
set -a; source .env; set +a

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="$REPO_ROOT/backups/$TIMESTAMP"
mkdir -p "$BACKUP_DIR"
LOG="$BACKUP_DIR/backup.log"

RED='\033[0;31m'; GREEN='\033[0;32m'; BOLD='\033[1m'; NC='\033[0m'

log()  { echo -e "$1" | tee -a "$LOG"; }
ok()   { log "${GREEN}  ✓ $1${NC}"; }
fail() { log "${RED}  ✗ $1${NC}"; exit 1; }

file_size() {
  local bytes
  bytes=$(stat -f%z "$1" 2>/dev/null || stat -c%s "$1" 2>/dev/null || echo "0")
  if   [[ $bytes -ge 1048576 ]]; then echo "$((bytes / 1048576)) MB"
  elif [[ $bytes -ge 1024    ]]; then echo "$((bytes / 1024)) KB"
  else echo "${bytes} B"; fi
}

log "${BOLD}=== Shift Happens Backup: $TIMESTAMP ===${NC}"
log "    Location: $BACKUP_DIR"
log ""

# ── MySQL ──────────────────────────────────────────────────────
log "[1/3] MySQL..."
docker exec shift-happens-db mysqldump \
  -u root -p"${MYSQL_ROOT_PASSWORD}" \
  --single-transaction --routines --triggers \
  "${MYSQL_DATABASE}" > "$BACKUP_DIR/mysql.sql" 2>>"$LOG"

[[ -s "$BACKUP_DIR/mysql.sql" ]] || fail "MySQL dump is empty"
ok "mysql.sql ($(file_size "$BACKUP_DIR/mysql.sql"))"

# ── MongoDB ────────────────────────────────────────────────────
log ""
log "[2/3] MongoDB..."
docker exec shift-happens-mongo mongodump \
  --db shift_happens --out /tmp/mongodump --quiet 2>>"$LOG"
docker cp shift-happens-mongo:/tmp/mongodump/shift_happens "$BACKUP_DIR/mongodb" 2>>"$LOG"
docker exec shift-happens-mongo rm -rf /tmp/mongodump 2>>"$LOG"

BSON_COUNT=$(find "$BACKUP_DIR/mongodb" -name "*.bson" | wc -l | tr -d ' ')
[[ $BSON_COUNT -gt 0 ]] || fail "MongoDB dump produced no .bson files"
ok "mongodb/ ($BSON_COUNT collections)"

# ── Neo4j ──────────────────────────────────────────────────────
log ""
log "[3/3] Neo4j (requires brief stop)..."
docker compose stop neo4j 2>>"$LOG"

docker compose run --rm --no-deps \
  -v "$BACKUP_DIR:/backup" \
  --entrypoint neo4j-admin \
  neo4j \
  database dump neo4j --to-path=/backup 2>>"$LOG"

docker compose start neo4j 2>>"$LOG"

[[ -s "$BACKUP_DIR/neo4j.dump" ]] || fail "Neo4j dump file not found or empty"
ok "neo4j.dump ($(file_size "$BACKUP_DIR/neo4j.dump"))"

# ── Summary ────────────────────────────────────────────────────
log ""
log "${GREEN}${BOLD}Backup complete.${NC}"
log "  mysql.sql    $(file_size "$BACKUP_DIR/mysql.sql")"
log "  mongodb/     $BSON_COUNT collections"
log "  neo4j.dump   $(file_size "$BACKUP_DIR/neo4j.dump")"
log "  log          $LOG"
