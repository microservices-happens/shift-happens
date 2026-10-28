# scheduling-service

**Owns:** shifts, required job roles per shift, shift assignments, shift approvals, **shift swaps** and swap approvals.
**Deployed as two CQRS roles from one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | API |
|---|---|---|---|
| `command` | `scheduling-command` | `scheduling-write-db` (Postgres, Flyway) + outbox + employee replica | REST writes: [`openapi/scheduling.yaml`](../../contracts/openapi/scheduling.yaml) |
| `query` | `scheduling-query` | `scheduling-read-db` (MongoDB) | GraphQL reads: [`graphql/scheduling.graphql`](../../contracts/graphql/scheduling.graphql) |

**Why CQRS here:** writes are small, validated transactions (assign, swap, approve). Reads are wide, nested views such as a week's schedule with assignments, employee names and AI suggestions. Mongo documents shaped for those views, served over GraphQL, avoid multi-table joins on every page load. The two roles also scale independently, since reads far outnumber writes.

**Why swaps live here:** a swap only reassigns an existing assignment. Approval and reassignment are one local transaction, so no saga is needed.

## Publishes (outbox)
- Shifts: `scheduling.shift.{created,updated,deleted}.v1`
- Assignments: `scheduling.assignment.{created,updated,deleted}.v1`
- Swaps: `scheduling.swap.{requested,approved,rejected}.v1`
- Understaffing: `scheduling.shift.understaffed.v1` (with pre-filtered candidates for ai-service)
- Saga replies: `scheduling.leave-release.{completed,rejected}.v1`

## Consumes
| Queue | Role | Routing keys | Handling |
|---|---|---|---|
| `scheduling.command` | command | `workforce.employee.*` | Upsert the local employee replica (version-based, tombstones win) |
| | | `leave.request.approval-started.v1` | **Saga step.** Release the overlapping assignments and reply `completed`. If any overlapping shift is `LOCKED`, reply `rejected` and change nothing |
| `scheduling.projection` | query | `scheduling.*`, `workforce.employee.*`, `ai.replacement.suggested.v1` | Upsert read-model documents (highest `aggregateVersion` wins) |

## Extract from monolith
`shift`, `shiftrequiredjobrole`, `shiftassignment`, `shiftapproval`, `shiftswap`, `shiftswapapproval` and `view/employeeshiftoverview` (now GraphQL).

## Done when
- [ ] The schedule page reads via GraphQL, and create, assign and swap work via REST.
- [ ] An approved swap moves the assignment, and both events reach notification and audit.
- [ ] Cooperation test: `leave.request.approval-started` → `leave-release.completed` or `rejected`.

## Status (2026-10-27)

The shift aggregate is complete on the command side and covered by tests:

| Endpoint | Result | Event written in the same transaction, then relayed |
|---|---|---|
| `POST /v1/shifts` | `202` + the shift | `scheduling.shift.created.v1` (snapshot) |
| `PUT /v1/shifts/{id}` | `202` + the shift | `scheduling.shift.updated.v1` (snapshot) |
| `DELETE /v1/shifts/{id}` | `202` | `scheduling.shift.deleted.v1` (tombstone) |

- PostgreSQL via Flyway (`V1__init.sql`): `shift`, `shift_required_job_role`, `outbox_event`. The payloads
  match `contracts/events/payloads/shift.schema.json` and `tombstone.schema.json`; the routing key is the
  `eventType`, so a consumer only has to bind a queue to `scheduling.shift.#`.
- The rules live in the domain, not in the controller: a positive duration, one row per job role, a new shift is
  `PLANNED` or `PUBLISHED`, the status only moves forward (`PLANNED → PUBLISHED → LOCKED`, anything →
  `CANCELLED`), `CANCELLED` is terminal, and `aggregateVersion` increments on every change. An illegal
  transition is `409 Conflict` in `problem+json`.
- DELETE is a **soft delete**: the row stays with `deleted = true` and a bumped version, and the event is a
  tombstone. A second `PUT` or `DELETE` is `404`, so a late update can never resurrect the shift.
- `Idempotency-Key` is required on POST and must be a UUID (`400` otherwise); the 24-hour replay store is P3.2.
- `ShiftTest` covers the domain rules; `ShiftCommandIT` covers REST → PostgreSQL → outbox → RabbitMQ against
  real engines in Testcontainers, including the tombstone and the `409`/`404` paths.
- The command role is `@Profile("command")` throughout, and `application-query.yml` excludes the
  Postgres/JPA/Flyway autoconfiguration, so the two CQRS roles cannot leak into each other.

Not implemented yet: `/v1/shiftrequiredjobroles`, assignments, approvals, swaps, the employee replica
(`workforce.employee.*` — mocked in tests until Workforce publishes `updated`/`deleted`), `shift.understaffed.v1`,
the query role with GraphQL, the leave-release saga reply, and JWT security (P1.7).

```bash
mvn -B -f services/scheduling-service/pom.xml verify   # Docker must be running (Testcontainers)
```
