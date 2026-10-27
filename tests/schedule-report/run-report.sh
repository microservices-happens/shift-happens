#!/usr/bin/env bash
#
# Nightly shift-risk report for the Shift Happens domain.
#
# Every file in queries/*.sql returns ONLY problem rows for one planning risk. This script runs
# them, counts the findings, renders a Markdown report, prints it, writes it to $REPORT_FILE and
# appends it to $GITHUB_STEP_SUMMARY when that variable is set (GitHub Actions).
#
# Environment:
#   MYSQL_HOST (127.0.0.1)   MYSQL_PORT (3306)      MYSQL_USER (root)
#   MYSQL_PASSWORD (rootpassword)                   MYSQL_DATABASE (shift_happens)
#   HORIZON_DAYS (7)         REPORT_FILE (schedule-risk-report.md)
#   MAX_ROWS_PER_CHECK (8)   GITHUB_OUTPUT          if set, "findings=<n>" is appended
#
# The exit code is always 0: the report informs planners, it does not gate a deployment. Add a
# threshold check at the end if the team wants a red nightly run above a risk limit.
#
# Data source: the monolith schema + seed (docker/init) in MySQL. When Scheduling and Leave expose
# their read models, replace the queries with calls to the GraphQL/REST APIs - see README.md.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
QUERY_DIR="$SCRIPT_DIR/queries"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-rootpassword}"
MYSQL_DATABASE="${MYSQL_DATABASE:-shift_happens}"
HORIZON_DAYS="${HORIZON_DAYS:-7}"
REPORT_FILE="${REPORT_FILE:-schedule-risk-report.md}"
MAX_ROWS_PER_CHECK="${MAX_ROWS_PER_CHECK:-8}"

# The seed data contains Danish characters, so pin the client charset: without it the client may
# fall back to latin1 and write "Reng°ring" instead of "Rengøring" into the report.
if command -v mysql >/dev/null 2>&1; then
  mysql_query() {
    MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --raw --default-character-set=utf8mb4 \
      -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" "$MYSQL_DATABASE" "$@"
  }
else
  # Fallback for machines without a MySQL client: run the client from the official image and reach
  # the server through the Docker host gateway. This also works on Windows and macOS, where
  # --network host is not available, so the script behaves the same everywhere.
  fallback_host="$MYSQL_HOST"
  case "$fallback_host" in
    127.0.0.1 | localhost) fallback_host="host.docker.internal" ;;
  esac
  mysql_query() {
    docker run --rm -i --add-host host.docker.internal:host-gateway \
      -e MYSQL_PWD="$MYSQL_PASSWORD" mysql:8.0 \
      mysql --batch --raw --default-character-set=utf8mb4 \
      -h "$fallback_host" -P "$MYSQL_PORT" -u "$MYSQL_USER" "$MYSQL_DATABASE" "$@"
  }
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

summary_rows="$tmp_dir/summary-rows.md"
sections="$tmp_dir/sections.md"
: > "$summary_rows"
: > "$sections"

total=0
for query_file in "$QUERY_DIR"/*.sql; do
  name="$(basename "$query_file" .sql)"
  title="$(sed -n '1s/^-- *title: *//p' "$query_file" | tr -d '\r')"
  description="$(sed -n '2s/^-- *description: *//p' "$query_file" | tr -d '\r')"
  [ -n "$title" ] || title="$name"

  result_file="$tmp_dir/$name.tsv"
  sed "s/__HORIZON_DAYS__/${HORIZON_DAYS}/g" "$query_file" | tr -d '\r' | mysql_query > "$result_file"

  count="$(tail -n +2 "$result_file" | grep -c . || true)"
  total=$((total + count))
  printf '| %s | %s |\n' "$title" "$count" >> "$summary_rows"

  {
    echo "## $title - $count finding(s)"
    echo
    if [ -n "$description" ]; then
      echo "_${description}_"
      echo
    fi
    if [ "$count" -eq 0 ]; then
      echo "No findings."
    else
      head -n "$((MAX_ROWS_PER_CHECK + 1))" "$result_file" | tr -d '\r' | awk -F'\t' '
        NR == 1 {
          header = $0; gsub(/\t/, " | ", header); print "| " header " |"
          separator = "|"
          for (i = 1; i <= NF; i++) separator = separator "---|"
          print separator
          next
        }
        { line = $0; gsub(/\t/, " | ", line); print "| " line " |" }
      '
      if [ "$count" -gt "$MAX_ROWS_PER_CHECK" ]; then
        echo
        echo "_Showing the first $MAX_ROWS_PER_CHECK of $count findings._"
      fi
    fi
    echo
  } >> "$sections"
done

{
  echo "# Shift risk report - $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo
  echo "Horizon: **${HORIZON_DAYS} days**. Data source: the \`docker/init\` schema and seed data in MySQL."
  echo
  echo "| check | findings |"
  echo "|---|---|"
  cat "$summary_rows"
  echo "| **total** | **$total** |"
  echo
  cat "$sections"
} > "$REPORT_FILE"

cat "$REPORT_FILE"
echo
echo "Total findings: $total"

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  cat "$REPORT_FILE" >> "$GITHUB_STEP_SUMMARY"
fi
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "findings=$total" >> "$GITHUB_OUTPUT"
fi
