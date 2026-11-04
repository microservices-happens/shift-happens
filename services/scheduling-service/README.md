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
| `scheduling.command` | command | `workforce.employee.*` | Upsert the local employee replica (version-based, tombstones win), including `weeklyContractHours` |
| | | `leave.request.approval-started.v1` | **Saga step.** Release the overlapping assignments and reply `completed`. If any overlapping shift is `LOCKED`, reply `rejected` and change nothing |
| | | `leave.request.approved.v1`, `leave.request.cancelled.v1` | Keep a copy of the leave windows, so the understaffed candidates leave out employees who are away |
| `scheduling.projection` | query | `scheduling.*`, `workforce.employee.*`, `ai.replacement.suggested.v1` | Upsert read-model documents (highest `aggregateVersion` wins) |

## Extract from monolith
`shift`, `shiftrequiredjobrole`, `shiftassignment`, `shiftapproval`, `shiftswap`, `shiftswapapproval` and `view/employeeshiftoverview` (now GraphQL).

## Done when
- [ ] The schedule page reads via GraphQL, and create, assign and swap work via REST.
- [ ] An approved swap moves the assignment, and both events reach notification and audit.
- [ ] Cooperation test: `leave.request.approval-started` → `leave-release.completed` or `rejected`.

## Status (2026-10-27)

The command role implements every contract operation it can implement today.

| Area | Done |
|---|---|
| Shifts | `POST`/`PUT`/`DELETE /v1/shifts`; the status only moves forward (`PLANNED → PUBLISHED → LOCKED`, anything → `CANCELLED`); delete is soft and publishes a tombstone |
| Required job roles | `POST`/`PUT`/`DELETE /v1/shiftrequiredjobroles`; the rows are part of the shift aggregate, so every change bumps its version and republishes `scheduling.shift.updated.v1` |
| Employee replica | consumes `workforce.employee.*`: idempotent (`processed_events`), highest `aggregateVersion` wins, a tombstone is retained and never cleared, and `weeklyContractHours` is stored for the candidate list |
| Assignments | `POST`/`PUT`/`DELETE /v1/shiftassignments`; the employee must be active and qualified **in the replica** and must not already work an overlapping shift (half-open `[start, end)`) |
| Approvals | `POST`/`PUT`/`DELETE /v1/shiftapprovals`; one decision per assignment, and `APPROVED` moves the assignment in the same transaction |
| Swaps | `POST`/`PUT`/`DELETE /v1/shiftswaps` and `POST /v1/shiftswapapprovals`; an approval hands the assignment over (`employeeId` changes, status `SWAPPED`) and publishes `assignment.updated.v1` + `swap.approved.v1` from one commit |
| Idempotency | every `POST` stores its response for 24 h: a replay returns it, a different body on the same key is `409` |
| Leave saga | consumes `leave.request.approval-started.v1`, releases the overlapping assignments, and replies `completed` or - if any of those shifts starts within 24 h, is `LOCKED` or was checked into - `rejected` with nothing changed. The answer is stored per leave request, so a redelivered request republishes the same reply |
| Understaffing | a cancelled, released or deleted assignment checks the remaining staffing per required role and publishes `scheduling.shift.understaffed.v1` in the same transaction, with candidates that are active, qualified, free elsewhere and **not on approved leave**, plus `hoursThisWeek` and `weeklyContractHours`. The hours and the leave windows are local copies (replica + `leave.request.approved/.cancelled`), so no synchronous call is made |

- Events match `contracts/events/payloads/{shift,assignment,swap,tombstone,leave-release,understaffed}.schema.json`; saga replies also set `causationId` to the event that triggered them.
- Every write commits its event through the outbox in the same transaction; the relay publishes with routing key = `eventType`.
- Migrations: `V1__init.sql`, `V2__employee_replica.sql`, `V3__assignments.sql`, `V4__idempotency.sql`, `V5__swaps.sql`, `V6__leave_release.sql`, `V7__understaffing_inputs.sql`.
- The queue has **one** listener that dispatches on `eventType`: two listeners on one queue would be competing consumers.
- Tests: 59 unit and 42 integration (`*IT` against real PostgreSQL and RabbitMQ, one shared pair of containers).

**Contract is ahead of two producers (deliberate).** Workforce does not publish `weeklyContractHours` yet (P2.6) and Leave does not publish `leave.request.approved.v1`/`.cancelled.v1` yet (P2.14); the contract now requires both, and the integration tests publish those envelopes built from the schemas. Scheduling needs no change when the producers catch up.

## Not implemented, and why

| Gap | Reason |
|---|---|
| `PUT`/`DELETE /v1/shiftswapapprovals` | The event catalog has no reversal flow (no `swap.updated`, no `swap.reverted`), so a decision is one-shot. An undo would mean inventing an event; left until the contract defines one |
| Query role, Mongo read model and GraphQL (P2.10/P2.11) | Next slice; every event it needs is being published now, including `ai.replacement.suggested.v1` from ai-service |
| JWT + role rules (P1.7) | Needs Identity's JWKS (P2.1). The claim shape is already fixed in `contracts/README.md`, so it is wiring once that service publishes `/.well-known/jwks.json` |

```bash
mvn -B -f services/scheduling-service/pom.xml verify   # Docker must be running (Testcontainers)
```