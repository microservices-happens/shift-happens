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
| | | `leave.request.approval-started.v1` | **Saga step (P3.1).** Release the overlapping assignments and reply `completed`. If any overlapping shift is `LOCKED`, reply `rejected` and change nothing |
| `scheduling.projection` | query | `scheduling.*`, `workforce.employee.*`, `ai.replacement.suggested.v1` | Upsert read-model documents (highest `aggregateVersion` wins) |

## Extract from monolith
`shift`, `shiftrequiredjobrole`, `shiftassignment`, `shiftapproval`, `shiftswap`, `shiftswapapproval` and `view/employeeshiftoverview` (now GraphQL).

## Done when
- [ ] The schedule page reads via GraphQL, and create, assign and swap work via REST.
- [ ] An approved swap moves the assignment, and both events reach notification and audit.
- [ ] Cooperation test: `leave.request.approval-started` → `leave-release.completed` or `rejected`.

## Status (2026-10-27)

| Area | Done |
|---|---|
| Shifts | `POST`/`PUT`/`DELETE /v1/shifts`; the status only moves forward (`PLANNED → PUBLISHED → LOCKED`, anything → `CANCELLED`); delete is soft and publishes a tombstone |
| Required job roles | `POST`/`PUT`/`DELETE /v1/shiftrequiredjobroles`; the role rows are part of the shift aggregate, so every change bumps its version and republishes `scheduling.shift.updated.v1` |
| Employee replica | consumes `workforce.employee.*` from `scheduling.command` into `employee_replica`: idempotent (`processed_events`), highest `aggregateVersion` wins, a tombstone is retained and never cleared |
| Assignments | `POST`/`PUT`/`DELETE /v1/shiftassignments`; an employee must be active and qualified **in the replica** and must not already work an overlapping shift (half-open `[start, end)`); delete publishes a tombstone |
| Approvals | `POST`/`PUT`/`DELETE /v1/shiftapprovals`; one decision per assignment, and `APPROVED` moves the assignment in the same transaction |

- Events match `contracts/events/payloads/{shift,assignment,tombstone}.schema.json`; `assignment.*` copies the shift window in so a consumer never looks the shift up.
- Every write commits its event through the outbox in the same transaction, and the relay publishes with routing key = `eventType`.
- Migrations: `V1__init.sql` (shift, job roles, outbox), `V2__employee_replica.sql`, `V3__assignments.sql`.
- Tests: `ShiftTest`, `RequiredJobRoleTest`, `EmployeeReplicaTest`, `AssignmentsTest` (38 unit) and `ShiftCommandIT`, `RequiredJobRoleCommandIT`, `EmployeeReplicaIT`, `AssignmentCommandIT` (27 integration, one shared PostgreSQL + RabbitMQ).

**Mocked boundary (deliberate):** Workforce currently publishes only `workforce.employee.created.v1`, so the tests publish `created`, `updated` and `deleted` envelopes built from the contracts. The consumer treats them exactly as production events, so the day P2.6 ships the other two, nothing here changes.

Not implemented yet: `/v1/shiftswaps` and `/v1/shiftswapapprovals` (P2.9), the `leave-release` saga reply (P3.1), `shift.understaffed.v1` with candidates (P2.12), the idempotency replay store (P3.2), the query role with GraphQL (P2.10/P2.11) and JWT security (P1.7).

```bash
mvn -B -f services/scheduling-service/pom.xml verify   # Docker must be running (Testcontainers)
```
