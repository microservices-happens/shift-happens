# workforce-service

**Owns:** employees, employee contracts, employee job roles, departments, job roles and work locations.
**Database:** `workforce-db` (Postgres, Flyway). **Compose:** `workforce-service`.

## Implemented so far (slice 1, P2.5/P2.6/P3.2)

- `POST /v1/employees`, `GET /v1/employees` and `GET /v1/employees/{id}` (contract shapes,
  `problem+json` errors, `X-Correlation-Id` copied into the event).
- Idempotent commands: every `POST` requires an `Idempotency-Key` (UUID). The first response is
  stored for 24 h and replayed unchanged; the same key with a different body is `409`.
- PostgreSQL write model `employee`, `outbox_event` and `idempotency_record`
  (Flyway `V1__init.sql`, `V2__idempotency.sql`).
- `workforce.employee.created.v1` written to the outbox in the same transaction as the employee, and
  published by `OutboxRelay` to `shift-happens.events` with routing key = eventType.
- Tests: `EmployeeTest` (domain), `WorkforceApplicationIT` (REST → PostgreSQL → outbox → RabbitMQ,
  including the idempotency replay and 409 paths).

- `PATCH /v1/employees/{id}` takes the Employee request body defined by OpenAPI and publishes
  an updated snapshot. `DELETE /v1/employees/{id}` soft-deletes the row and publishes a tombstone.
  Deleted employees are hidden from reads and cannot be updated.
- `GET /v1/employees` accepts optional filters. `employmentStatus`, `primaryWorkLocationId` and
  `userRole` match exactly. `email`, `firstName` and `lastName` match case-insensitive substrings.
  When multiple filters are provided, all must match.

- Departments, job roles and work locations support list, create, read, update and delete.
  Departments use PATCH for updates. Job roles and work locations use PUT.
  POST requires an `Idempotency-Key` and replays the stored response on a retry.

- Employee contracts and employee job-role assignments support list, create, read, update and delete.
  Updates use PUT and POST requires an `Idempotency-Key`. Changes publish a versioned employee
  snapshot with currently valid role IDs and weekly hours from the latest active contract.
  Start and end dates are inclusive and evaluated in UTC when a snapshot is published.
  Workforce stores the next date to check in PostgreSQL and polls for due employees every minute.
  A changed snapshot is written to the outbox in the same transaction as the next scheduled check.

Still to do: JWT security (P1.7).

## Local demo data (P1.9)

`seed/dev-seed.sql` holds demo data ported from the monolith's `docker/init/02-seed-data.sql`:
4 work locations, 7 departments, 8 job roles and 13 made-up employees (`@shift-happens.example`)
with contracts and job roles. It lives outside `src/`, so it is never in the jar, the image or a
Flyway migration, and no test except `DevSeedIT` reads it. Nothing loads it unless you run it.

| Id | Number | Role | Name | Note |
|---|---|---|---|---|
| 1 | DEMO-0001 | Administrator | Mette Lindholm | `admin@shift-happens.example` |
| 2, 3 | DEMO-0002/3 | Manager | Sofie Jensen, Mads Nielsen | |
| 4-11 | DEMO-0004..11 | Employee | | DEMO-0008's nurse role expires in 90 days |
| 12 | DEMO-0012 | Employee | Frederik Larsen | `INACTIVE`, cannot log in |
| 13 | DEMO-0013 | Employee | Julie Madsen | Starts in 14 days: no roles or hours until then |

Only workforce-db is written directly. The script also writes one `workforce.employee.created.v1`
per employee to the outbox in the same transaction (today's full snapshot, version 1, the same
`jobRoleIds`/`weeklyContractHours` and terms-refresh schedule `EmployeeService` would produce), and
the running `OutboxRelay` publishes them. identity, scheduling and leave (command and query) build
their copies through their normal consumers, so the ids match everywhere and every consumer does its
own `processed_events`/`aggregateVersion` bookkeeping. Departments, job roles and locations are not
replicated; other services only see job-role ids inside the employee snapshot.

```bash
docker compose -f docker-compose.microservices.yml --profile app up -d --build
# wait until the services are healthy, so their queues exist
scripts/seed-dev.sh
```

`scripts/seed-dev.sh` refuses to run until workforce-db is migrated and `identity.accounts` exists,
and warns when a scheduling or leave queue is missing (that service would miss the events). The SQL
only runs on an empty workforce database and is a no-op the second time; to start over, use
`docker compose -f docker-compose.microservices.yml down -v`.

Logins: set `BOOTSTRAP_ADMIN_EMAIL=admin@shift-happens.example` and a `BOOTSTRAP_ADMIN_PASSWORD` in
`.env` before starting identity-service, and the seeded Administrator gets that password when its
event arrives. Set `SEED_DEMO_PASSWORD` (min. 8 characters) as well, and the script logs in as that
admin through the gateway and sets it on every other active demo account
(`PUT /api/v1/auth/accounts/{id}/password`), so identity hashes it with its own pepper. The repository
contains no passwords or hashes.

## Provides
REST: [`contracts/openapi/workforce.yaml`](../../contracts/openapi/workforce.yaml). CRUD under `/v1/employees`, `/v1/employeecontracts`, `/v1/employeejobroles`, `/v1/departments`, `/v1/jobroles` and `/v1/worklocations`.

## Publishes (outbox)
| Event | When |
|---|---|
| `workforce.employee.created.v1` | Employee created |
| `workforce.employee.updated.v1` | Any employee field changes, or their job roles change (`jobRoleIds` is part of the snapshot) |
| `workforce.employee.deleted.v1` | Soft delete (tombstone) |

This is the only source of employee data for other services. Nobody calls Workforce synchronously.

## Consumes
Nothing.

## Extract from monolith
`employee`, `employeecontract`, `employeejobrole`, `department`, `jobrole` and `worklocation`. `EmployeeDto.loginPassword` is removed: passwords now live in identity-service.

## API docs (Swagger UI)

Swagger UI is at `http://localhost:8080/swagger-ui.html` (service port; through the gateway it is not routed). It renders
the hand-written contract `contracts/openapi/workforce.yaml`, not a spec generated from the controllers. Maven copies
the file onto the classpath at build time (`/openapi/workforce.yaml`); never edit the copy. The Docker build gets the file
through `--build-context contracts=contracts` (set in Compose and CI).

## Done when
- [ ] Employee pages work through the gateway.
- [ ] Creating an employee reaches identity, scheduling, leave and audit (integration test on the outbox and RabbitMQ).
