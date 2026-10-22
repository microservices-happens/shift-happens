# Nightly shift-risk report

A scheduled pipeline (`nightly-shift-risk.yml`) that answers the questions a shift planner asks
every morning:

| Query | Question |
|---|---|
| `queries/01-understaffed-upcoming.sql` | Which planned shifts in the horizon are short of staff? |
| `queries/02-understaffed-locked-window.sql` | Which of them start within 24 hours (locked window, the leave saga cannot release them)? |
| `queries/03-double-booked-employees.sql` | Is anyone assigned to two overlapping shifts? |
| `queries/04-approved-leave-overlap.sql` | Is approved leave still sitting on the schedule instead of being released? |

## How it runs

```
nightly-shift-risk.yml (cron 03:00 UTC, or manual)
└── MySQL 8 service container
    ├── docker/init/01-schema.sql           (tables)
    ├── docker/init/02-seed-data.sql        (generated seed, dates relative to CURDATE())
    └── seed-demo-issues.sql                (3 deliberate findings so the demo is never empty)
└── run-report.sh
    ├── every query in queries/*.sql
    ├── schedule-risk-report.md             (artifact)
    ├── $GITHUB_STEP_SUMMARY                (the run page shows the report inline)
    └── findings=<n>                        (job output consumed by the notify job)
```

## Run it locally

```bash
# 1. Start a MySQL and load the data (Docker Compose has one for the monolith, or use any MySQL 8)
docker run --rm -d --name shift-report-db -e MYSQL_ROOT_PASSWORD=rootpassword \
  -e MYSQL_DATABASE=shift_happens -p 3306:3306 mysql:8.0

# 2. Wait a few seconds, then load schema + seed + demo findings
mysql -h 127.0.0.1 -uroot -prootpassword < docker/init/01-schema.sql
mysql -h 127.0.0.1 -uroot -prootpassword shift_happens < docker/init/02-seed-data.sql
mysql -h 127.0.0.1 -uroot -prootpassword shift_happens < tests/schedule-report/seed-demo-issues.sql

# 3. Build the report (Git Bash / WSL / Linux / macOS)
MYSQL_PASSWORD=rootpassword HORIZON_DAYS=7 tests/schedule-report/run-report.sh
cat schedule-risk-report.md
```

Environment variables: `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_DATABASE`,
`HORIZON_DAYS` (default 7), `MAX_ROWS_PER_CHECK` (default 8), `REPORT_FILE`. Without a local `mysql`
client the script falls back to `docker run mysql:8.0 mysql`.

## Adding a check

1. Add `queries/NN-name.sql` returning **only problem rows**, with the first two lines as metadata:

   ```sql
   -- title: Short title used in the report table
   -- description: One sentence explaining why a row is a problem.
   ```

2. Use `__HORIZON_DAYS__` where a horizon is needed; the script substitutes it.
3. No other change is needed: the workflow, the report and the findings count pick it up.

## Where this goes next

The queries read the monolith tables today because the services are still skeletons. Once the read
models exist, the same checks should be reimplemented as one of:

* a GraphQL query against `scheduling-query` (`understaffedShifts`, `swapRequests`) plus a Leave
  balance/overlap query, or
* an `*IT` test in `tests/cooperation` that seeds two services and asserts the same rules, or
* a serverless job that consumes `scheduling.shift.understaffed.v1` events and reports them.

The report format, the schedule and the issue creation stay exactly the same, which is the point of
keeping the checks in SQL/scripts instead of inside the pipeline YAML.
