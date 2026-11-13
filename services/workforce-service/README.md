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

## Done when
- [ ] Employee pages work through the gateway.
- [ ] Creating an employee reaches identity, scheduling, leave and audit (integration test on the outbox and RabbitMQ).
