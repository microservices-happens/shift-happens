-- Demo data for a LOCAL workforce-db (P1.9). Never part of the image or of a Flyway migration.
--
-- Run it with scripts/seed-dev.sh (or psql -f) after workforce-service has started once, so Flyway
-- has created the schema. It is a psql script: it uses \gset/\if and must not be run through JDBC.
--
-- Only workforce is seeded directly. Every other service gets the employees the normal way: this
-- script writes one workforce.employee.created.v1 per employee to the outbox, in the same
-- transaction as the rows, and the running OutboxRelay publishes them. identity, scheduling
-- (command + query) and leave (command + query) then build their replicas and read models through
-- their own consumers, with their own processed_events and aggregateVersion bookkeeping. Their
-- queues must exist before the relay publishes (scripts/seed-dev.sh checks this).
--
-- All people, emails and phone numbers are made up (RFC 2606 .example domain). No passwords:
-- identity gives the Administrator its first password through BOOTSTRAP_ADMIN_EMAIL/PASSWORD.
--
-- The script only runs on an empty database and is a no-op when it has already been loaded.

\set ON_ERROR_STOP on

SELECT EXISTS (SELECT 1 FROM employee WHERE employee_number = 'DEMO-0001') AS already_seeded,
       (EXISTS (SELECT 1 FROM employee)
           OR EXISTS (SELECT 1 FROM department)
           OR EXISTS (SELECT 1 FROM job_role)
           OR EXISTS (SELECT 1 FROM work_location)) AS has_data
\gset

\if :already_seeded
\echo 'dev seed: already loaded, nothing to do'
\quit
\elif :has_data
DO $$
BEGIN
    RAISE EXCEPTION 'dev seed: workforce-db already has data; the seed only runs on an empty database (reset: docker compose -f docker-compose.microservices.yml down -v)';
END
$$;
\endif

BEGIN;

-- ---------------------------------------------------------------- reference data

INSERT INTO work_location (work_location_id, location_name, address_line_1, address_line_2, city, country,
                           timezone, is_active)
VALUES (1, 'Rigshospitalet', 'Blegdamsvej 9', NULL, 'København', 'Danmark', 'Europe/Copenhagen', TRUE),
       (2, 'Aarhus Universitetshospital', 'Palle Juul-Jensens Blvd. 99', NULL, 'Aarhus', 'Danmark',
        'Europe/Copenhagen', TRUE),
       (3, 'Odense Universitetshospital', 'J.B. Winsløws Vej 4', NULL, 'Odense', 'Danmark',
        'Europe/Copenhagen', TRUE),
       (4, 'Aalborg Universitetshospital', 'Hobrovej 18-22', NULL, 'Aalborg', 'Danmark',
        'Europe/Copenhagen', FALSE);

INSERT INTO department (department_id, department_name, is_active)
VALUES (1, 'Akutmodtagelse', TRUE),
       (2, 'Intensiv', TRUE),
       (3, 'Kirurgisk afdeling', TRUE),
       (4, 'Medicinsk afdeling', TRUE),
       (5, 'Børneafdeling', TRUE),
       (6, 'Administration', TRUE),
       (7, 'Kantine', FALSE);

INSERT INTO job_role (job_role_id, role_name, job_role_description, is_certification_required)
VALUES (1, 'Sygeplejerske', 'Uddannet sygeplejerske', TRUE),
       (2, 'Læge', 'Speciallæge', TRUE),
       (3, 'Afdelingsleder', 'Leder af afdeling', FALSE),
       (4, 'Social- og sundhedsassistent', 'Støtter patientpleje', FALSE),
       (5, 'Receptionist', 'Reception og patientmodtagelse', FALSE),
       (6, 'Bioanalytiker', 'Laboratoriearbejde', TRUE),
       (7, 'Portør', 'Transport af patienter', FALSE),
       (8, 'IT-supporter', 'Teknisk support', FALSE);

-- ---------------------------------------------------------------- employees
-- 1 Administrator, 2 Managers, 10 Employees. DEMO-0012 is INACTIVE (identity disables the login),
-- DEMO-0013 starts in two weeks (no roles or hours yet; the terms refresh publishes them then).

INSERT INTO employee (employee_id, employee_number, user_role, first_name, last_name, email, phone_number,
                      hire_date, employment_status, primary_work_location_id, aggregate_version, deleted)
VALUES (1, 'DEMO-0001', 'Administrator', 'Mette', 'Lindholm', 'admin@shift-happens.example', '+45 50000001',
        DATE '2019-01-01', 'ACTIVE', 1, 1, FALSE),
       (2, 'DEMO-0002', 'Manager', 'Sofie', 'Jensen', 'sofie.jensen@shift-happens.example', '+45 50000002',
        DATE '2018-03-15', 'ACTIVE', 1, 1, FALSE),
       (3, 'DEMO-0003', 'Manager', 'Mads', 'Nielsen', 'mads.nielsen@shift-happens.example', '+45 50000003',
        DATE '2019-07-01', 'ACTIVE', 2, 1, FALSE),
       (4, 'DEMO-0004', 'Employee', 'Ida', 'Hansen', 'ida.hansen@shift-happens.example', '+45 50000004',
        DATE '2020-01-20', 'ACTIVE', 1, 1, FALSE),
       (5, 'DEMO-0005', 'Employee', 'Emma', 'Pedersen', 'emma.pedersen@shift-happens.example', '+45 50000005',
        DATE '2021-05-05', 'ACTIVE', 1, 1, FALSE),
       (6, 'DEMO-0006', 'Employee', 'Noah', 'Andersen', 'noah.andersen@shift-happens.example', '+45 50000006',
        DATE '2016-09-23', 'ACTIVE', 1, 1, FALSE),
       (7, 'DEMO-0007', 'Employee', 'Laura', 'Christensen', 'laura.christensen@shift-happens.example',
        '+45 50000007', DATE '2015-02-14', 'ACTIVE', 1, 1, FALSE),
       (8, 'DEMO-0008', 'Employee', 'Oliver', 'Poulsen', 'oliver.poulsen@shift-happens.example', '+45 50000008',
        DATE '2019-08-27', 'ACTIVE', 2, 1, FALSE),
       (9, 'DEMO-0009', 'Employee', 'Josefine', 'Thomsen', 'josefine.thomsen@shift-happens.example',
        '+45 50000009', DATE '2022-04-18', 'ACTIVE', 2, 1, FALSE),
       (10, 'DEMO-0010', 'Employee', 'Katrine', 'Mortensen', 'katrine.mortensen@shift-happens.example', NULL,
        DATE '2023-01-09', 'ACTIVE', 2, 1, FALSE),
       (11, 'DEMO-0011', 'Employee', 'Andreas', 'Olsen', 'andreas.olsen@shift-happens.example', '+45 50000011',
        DATE '2018-09-03', 'ACTIVE', 3, 1, FALSE),
       (12, 'DEMO-0012', 'Employee', 'Frederik', 'Larsen', 'frederik.larsen@shift-happens.example',
        '+45 50000012', DATE '2017-11-10', 'INACTIVE', 3, 1, FALSE),
       (13, 'DEMO-0013', 'Employee', 'Julie', 'Madsen', 'julie.madsen@shift-happens.example', '+45 50000013',
        (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 14, 'ACTIVE', 1, 1, FALSE);

-- Salaries are made up and never leave workforce (they are not part of the event snapshot).
INSERT INTO employee_contract (contract_id, employee_id, department_id, contract_type, start_date, end_date,
                               weekly_hours, salary_amount, is_active)
VALUES (1, 1, 6, 'FULL_TIME', DATE '2019-01-01', NULL, 37, 52000.00, TRUE),
       (2, 2, 1, 'FULL_TIME', DATE '2018-03-15', NULL, 37, 48000.00, TRUE),
       (3, 3, 3, 'FULL_TIME', DATE '2019-07-01', NULL, 37, 61000.00, TRUE),
       (4, 4, 1, 'FULL_TIME', DATE '2020-01-20', NULL, 37, 38000.00, TRUE),
       (5, 5, 2, 'PART_TIME', DATE '2021-05-05', NULL, 30, 33000.00, TRUE),
       (6, 6, 1, 'FULL_TIME', DATE '2016-09-23', NULL, 37, 58000.00, TRUE),
       (7, 7, 4, 'PART_TIME', DATE '2015-02-14', NULL, 25, 27000.00, TRUE),
       (8, 8, 3, 'FULL_TIME', DATE '2019-08-27', NULL, 37, 37500.00, TRUE),
       (9, 9, 4, 'FULL_TIME', DATE '2022-04-18', NULL, 37, 36000.00, TRUE),
       (10, 10, 5, 'TEMP', DATE '2023-01-09', NULL, 20, 21000.00, TRUE),
       (11, 11, 6, 'FULL_TIME', DATE '2018-09-03', NULL, 37, 41000.00, TRUE),
       (12, 12, 4, 'FULL_TIME', DATE '2017-11-10', DATE '2024-06-30', 37, 35000.00, TRUE),
       (13, 13, 2, 'FULL_TIME', (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 14, NULL, 37, 36500.00, TRUE);

-- DEMO-0008's nurse qualification expires in 90 days, so the terms refresh has something to publish.
INSERT INTO employee_job_role (employee_job_role_id, employee_id, job_role_id, assigned_date, expiry_date,
                               proficiency_level)
VALUES (1, 2, 3, DATE '2018-03-15', NULL, 'ADVANCED'),
       (2, 2, 1, DATE '2018-03-15', NULL, 'ADVANCED'),
       (3, 3, 3, DATE '2019-07-01', NULL, 'INTERMEDIATE'),
       (4, 3, 2, DATE '2019-07-01', NULL, 'ADVANCED'),
       (5, 4, 1, DATE '2020-01-20', NULL, 'INTERMEDIATE'),
       (6, 5, 1, DATE '2021-05-05', NULL, 'BEGINNER'),
       (7, 5, 4, DATE '2021-05-05', NULL, 'INTERMEDIATE'),
       (8, 6, 2, DATE '2016-09-23', NULL, 'ADVANCED'),
       (9, 7, 4, DATE '2015-02-14', NULL, 'ADVANCED'),
       (10, 8, 1, DATE '2019-08-27', (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 90, 'INTERMEDIATE'),
       (11, 9, 6, DATE '2022-04-18', NULL, 'INTERMEDIATE'),
       (12, 10, 7, DATE '2023-01-09', NULL, 'BEGINNER'),
       (13, 10, 5, DATE '2023-01-09', NULL, 'BEGINNER'),
       (14, 11, 8, DATE '2018-09-03', NULL, 'ADVANCED'),
       (15, 12, 7, DATE '2017-11-10', DATE '2024-06-30', 'INTERMEDIATE'),
       (16, 13, 1, (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + 14, NULL, 'BEGINNER');

-- Explicit ids above, so move every sequence past them; the next REST create gets a fresh id.
SELECT setval(pg_get_serial_sequence('work_location', 'work_location_id'), (SELECT max(work_location_id) FROM work_location));
SELECT setval(pg_get_serial_sequence('department', 'department_id'), (SELECT max(department_id) FROM department));
SELECT setval(pg_get_serial_sequence('job_role', 'job_role_id'), (SELECT max(job_role_id) FROM job_role));
SELECT setval(pg_get_serial_sequence('employee', 'employee_id'), (SELECT max(employee_id) FROM employee));
SELECT setval(pg_get_serial_sequence('employee_contract', 'contract_id'), (SELECT max(contract_id) FROM employee_contract));
SELECT setval(pg_get_serial_sequence('employee_job_role', 'employee_job_role_id'),
              (SELECT max(employee_job_role_id) FROM employee_job_role));

-- ---------------------------------------------------------------- terms refresh schedule
-- Same rule as EmployeeTermsRepository.reschedule(employee, today): the next contract or job-role
-- boundary after today, or no row at all. evaluated_on = today because the snapshot below is today's.

INSERT INTO employee_terms_refresh (employee_id, due_on, evaluated_on)
SELECT e.employee_id, next_boundary.due_on, today.d
FROM employee e
CROSS JOIN (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date AS d) today
CROSS JOIN LATERAL (
    SELECT min(boundary) AS due_on
    FROM (SELECT c.start_date AS boundary FROM employee_contract c WHERE c.employee_id = e.employee_id AND c.is_active
          UNION ALL
          SELECT c.end_date + 1 FROM employee_contract c WHERE c.employee_id = e.employee_id AND c.is_active
          UNION ALL
          SELECT r.assigned_date FROM employee_job_role r WHERE r.employee_id = e.employee_id
          UNION ALL
          SELECT r.expiry_date + 1 FROM employee_job_role r WHERE r.employee_id = e.employee_id) dates
    WHERE boundary > today.d
) next_boundary
WHERE next_boundary.due_on IS NOT NULL;

-- ---------------------------------------------------------------- outbox
-- One workforce.employee.created.v1 per employee: the envelope EmployeeService writes
-- (contracts/events/envelope.schema.json) with today's full snapshot (employee.schema.json), i.e. the
-- same jobRoleIds and weeklyContractHours EmployeeTermsRepository computes. Never salary or password.

WITH run AS (SELECT gen_random_uuid()::text AS correlation_id),
     today AS (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date AS d),
     -- Volatile functions in a select list run once per row: one eventId and timestamp per employee.
     events AS (SELECT e.employee_id, gen_random_uuid() AS event_id, clock_timestamp() AS occurred_at
                FROM employee e
                ORDER BY e.employee_id)
INSERT INTO outbox_event (event_id, event_type, aggregate_id, payload, occurred_at)
SELECT ev.event_id,
       'workforce.employee.created.v1',
       e.employee_id,
       json_build_object(
           'eventId', ev.event_id,
           'eventType', 'workforce.employee.created.v1',
           'occurredAt', to_char(ev.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
           'producer', 'workforce-service',
           'aggregateId', e.employee_id,
           'aggregateVersion', e.aggregate_version,
           'correlationId', run.correlation_id,
           'causationId', NULL,
           'data', json_build_object(
               'employeeId', e.employee_id,
               'employeeNumber', e.employee_number,
               'userRole', e.user_role,
               'firstName', e.first_name,
               'lastName', e.last_name,
               'email', e.email,
               'phoneNumber', e.phone_number,
               'hireDate', e.hire_date,
               'employmentStatus', e.employment_status,
               'primaryWorkLocationId', e.primary_work_location_id,
               'jobRoleIds', COALESCE(
                   (SELECT json_agg(r.job_role_id ORDER BY r.job_role_id)
                    FROM employee_job_role r
                    WHERE r.employee_id = e.employee_id
                      AND r.assigned_date <= today.d
                      AND (r.expiry_date IS NULL OR r.expiry_date >= today.d)),
                   '[]'::json),
               'weeklyContractHours', COALESCE(
                   (SELECT COALESCE(c.weekly_hours, 0)
                    FROM employee_contract c
                    WHERE c.employee_id = e.employee_id
                      AND c.is_active
                      AND c.start_date <= today.d
                      AND (c.end_date IS NULL OR c.end_date >= today.d)
                    ORDER BY c.start_date DESC, c.contract_id DESC
                    LIMIT 1),
                   0))
       )::text,
       ev.occurred_at
FROM events ev
JOIN employee e ON e.employee_id = ev.employee_id
CROSS JOIN run
CROSS JOIN today;

COMMIT;

\echo 'dev seed: loaded 13 employees; workforce-service publishes their events within seconds'
