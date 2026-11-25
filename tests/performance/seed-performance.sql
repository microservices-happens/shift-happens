-- Performance fixture for P-04: at least 1,000 employee records.
--
-- This is a LOCAL performance fixture, not application seed data and not a Flyway migration.
-- It writes only the Workforce write model, so the employee list/read API can be measured with a
-- realistic table size. Use it only against a disposable local workload:
--
--   Get-Content tests/performance/seed-performance.sql -Raw |
--     docker compose -f docker-compose.microservices.yml exec -T workforce-db `
--       psql -v ON_ERROR_STOP=1 -U workforce -d workforce
--
-- The script is idempotent: existing PERF-* rows are skipped.

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO employee
    (employee_number, user_role, first_name, last_name, email, phone_number,
     hire_date, employment_status, primary_work_location_id, aggregate_version, deleted)
SELECT
    'PERF-' || lpad(n::text, 4, '0'),
    'Employee',
    'Performance',
    'Employee ' || n,
    'perf-' || n || '@shift-happens.example',
    NULL,
    CURRENT_DATE - 30,
    'ACTIVE',
    1,
    1,
    FALSE
FROM generate_series(1, 1000) AS n
ON CONFLICT (employee_number) DO NOTHING;

COMMIT;

SELECT count(*) AS employee_count FROM employee;
SELECT count(*) AS performance_employee_count FROM employee WHERE employee_number LIKE 'PERF-%';
