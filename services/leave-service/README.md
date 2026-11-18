# leave-service

**Owns:** leave types, leave requests, leave approvals, the **immutable leave ledger** and the leave-approval **saga**.
**Deployed as two CQRS roles from one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | API |
|---|---|---|---|
| `command` | `leave-command` | `leave-write-db` (Postgres, Flyway) + outbox + employee replica (the saga table arrives with #47) | REST POST/PUT/PATCH/DELETE |
| `query` | `leave-query` | `leave-read-db` (MongoDB) | REST GET (balances, overviews) |

> **Status:** the command side has its foundation (transactional outbox + relay, DLX topology, `processed_events`, `Idempotency-Key`, RFC 9457 problem+json, the employee replica fed by `workforce.employee.*`, the `leave.command` queue) and, since #44, the **write API for leave types, leave requests and leave approvals** (see "Command API (#44)"). Still missing: the immutable ledger and balance snapshots (#45), the saga and therefore `APPROVED` decisions plus cancelling approved leave (#47 / P3.1), the query role and all GET endpoints (#46), and JWT security with role checks (P2.1). The sections below describe the target design.

REST contract: [`contracts/openapi/leave.yaml`](../../contracts/openapi/leave.yaml).

## Command API (#44)
Writes only; GETs belong to the query role (#46). All writes answer `202 Accepted` with the resource, errors are problem+json (`400` invalid input, `404` unknown id, `409` business rule). Schema `V3__leave_domain.sql`; the `employee_replica` is checked, never another service.

| Endpoint | Rules | Event |
|---|---|---|
| `POST /v1/leavetypes` (`Idempotency-Key`) | name unique ignoring case (`409`); `requiresApproval` and `isPaidLeave` default to `true` | none (not in the catalog) |
| `PUT /v1/leavetypes/{id}` | same validation; `404` when missing | none |
| `DELETE /v1/leavetypes/{id}` | hard delete; `409` while any leave request uses the type | none |
| `POST /v1/leaverequests` (`Idempotency-Key`) | `endDate >= startDate` (`400`); leave type must exist (`400`); employee must be active in the replica (`409`, the replica may lag); no overlap with the employee's PENDING / APPROVAL_PENDING / APPROVED requests (`409`). `endDate` is the last day of leave, so 1st-5th and 5th-7th overlap. Always starts `PENDING`, even when the type does not require approval (auto-approval is out of scope) | `leave.request.submitted.v1` |
| `PATCH /v1/leaverequests/{id}` | only while `PENDING` (`409`); optional `leaveTypeId`, `startDate`, `endDate`, `reason`, same validations, overlap ignores itself; a PATCH that changes nothing returns the current request (`202`, no version bump, no event); no `Idempotency-Key` (as in scheduling's PUT) | `leave.request.submitted.v1` again, with a higher `aggregateVersion` |
| `DELETE /v1/leaverequests/{id}` | only `PENDING` becomes `CANCELLED` (the row stays); a repeated DELETE on a `CANCELLED` request is an idempotent `202` no-op (no change, no second event); any other status is `409` (#47 adds APPROVED with a REVERSAL ledger entry) | `leave.request.cancelled.v1` (first cancel only) |
| `POST /v1/leaveapprovals` (`Idempotency-Key`) | approver must be active in the replica (`409`), unknown `leaveRequestId` is `404`. `REJECTED`: request must be `PENDING` (`409`), becomes `REJECTED` with `rejectionReason` = comment (default "Rejected by manager") | `leave.request.rejected.v1` with the `decision` object |
| | **`APPROVED` is a placeholder:** `409` "Approval not available yet" (the leave-approval saga is not implemented, P3.1); no state change, no event. `LeaveRequest.startApproval` is the one place #47 replaces | none |
| `PUT /v1/leaveapprovals/{id}` | every stored decision is final today, so `404` when missing, otherwise `409` | none |

`aggregateVersion` starts at 1 and increments on every change of a request. `DELETE /v1/leaveapprovals/{id}` is not implemented. Role checks (Administrator/Manager) wait for the JWT work (P2.1). 

### Concurrency
- **Edit, cancel and reject** lock the `leave_request` row (`SELECT ... FOR UPDATE`), so concurrent changes of one request run one after the other and the later one sees the committed state (a reject racing a cancel gets `409`).
- **Overlap rule:** `submit` and `edit` take a per-employee advisory lock (`pg_advisory_xact_lock(employeeId)`) before checking the employee's other requests, so two overlapping requests of one employee cannot both pass the check.
- **`Idempotency-Key`:** `IdempotencyService.execute` is one transaction that first takes an advisory lock on the (canonical, lower-case) key (`pg_advisory_xact_lock(4401, hash(key))`), so two simultaneous calls with the same key run the action once and the second one replays the first response. A failing command rolls back its effects together with the idempotency record, so failures are not stored and a retry with the same key runs again.
- **Backstop:** the exclusion constraint (`btree_gist`) still guards the overlap rule in the database. A lost lock race or deadlock (`ConcurrencyFailureException`) and a constraint violation are answered as `409` "Conflicting change" problem+json; the client may retry.

## Immutable ledger + snapshot
Ledger rows are never updated or deleted. A correction or saga compensation is a new `REVERSAL` entry. For fast balances, the command side stores a **balance snapshot** per employee and leave type every 50 entries: balance = latest snapshot + the entries after it.

## Leave-approval saga (orchestrator)
See [`catalog.md`](../../contracts/events/catalog.md#flow-leave-approval-saga-orchestrated-by-leave-command).

| Step | On | Action |
|---|---|---|
| 1 | Manager approves | The request becomes `APPROVAL_PENDING`, a `RESERVATION` entry is appended, a saga row with a deadline is created, and it publishes `leave.request.approval-started.v1` |
| 2a | `scheduling.leave-release.completed.v1` | The request becomes `APPROVED` and it publishes `leave.request.approved.v1` |
| 2b | `scheduling.leave-release.rejected.v1` or the deadline passes | **Compensate:** append a `REVERSAL` entry, set the request to `REJECTED` with a reason, and publish `leave.request.rejected.v1` |

## Publishes (outbox)
Today: `leave.request.submitted.v1` (submit and every changing edit), `leave.request.cancelled.v1` (cancel of a `PENDING` request; a repeated DELETE is a `202` no-op and publishes nothing) and `leave.request.rejected.v1`.
Planned: `leave.request.approval-started.v1`, `leave.request.approved.v1` (#47) and `leave.ledger.entry-recorded.v1` (#45).

The relay polls the outbox every `OUTBOX_RELAY_DELAY` ms (default `2000`).

## Consumes
| Queue | Role | Routing keys |
|---|---|---|
| `leave.command` | command | exact keys `workforce.employee.created.v1`, `workforce.employee.updated.v1`, `workforce.employee.deleted.v1` (local employee replica for validating requests); `scheduling.leave-release.{completed,rejected}.v1` (saga replies) come with #47 |
| `leave.projection` | query | `leave.*`, `workforce.employee.*` |

A message that fails three times (or cannot be converted) is republished to the dead-letter exchange `shift-happens.dlx` with routing key `leave.command` (the queue name) and lands in `leave.command.dlq`; it is never requeued forever.

> **Upgrading a local broker:** the arguments (`x-dead-letter-routing-key`) and bindings of `leave.command` changed. RabbitMQ refuses to redeclare a queue with different arguments, so delete the existing `leave.command` queue (or run `docker compose -f docker-compose.microservices.yml down -v`) before starting the service.

## Extract from monolith
`leavetype`, `leaverequest`, `leaveapproval`, `leaveledger` and `view/employeeleaveoverview`. Remove PUT/DELETE on the ledger.

## Done when
- [ ] Both saga outcomes are covered by a cooperation test (Leave + Scheduling + RabbitMQ in Testcontainers).
- [ ] The balance in the read model equals the sum of the ledger after replay in any order.
