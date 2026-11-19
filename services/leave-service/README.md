# leave-service

**Owns:** leave types, leave requests, leave approvals, the **immutable leave ledger** and the leave-approval **saga**.
**Deployed as two CQRS roles from one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | API |
|---|---|---|---|
| `command` | `leave-command` | `leave-write-db` (Postgres, Flyway) + outbox + employee replica (the saga table arrives with #47) | REST POST/PUT/PATCH/DELETE |
| `query` | `leave-query` | `leave-read-db` (MongoDB) | REST GET (balances, overviews) |

> **Status:** the command side has its foundation (transactional outbox + relay, DLX topology, `processed_events`, `Idempotency-Key`, RFC 9457 problem+json, the employee replica fed by `workforce.employee.*`, the `leave.command` queue) and, since #44, the **write API for leave types, leave requests and leave approvals** (see "Command API (#44)") and, since #45, the **immutable ledger with balance snapshots** and `POST /v1/leaveledgers` (see "Immutable ledger + snapshot"). Still missing: the saga and therefore `APPROVED` decisions plus cancelling approved leave (#47 / P3.1), the query role and all GET endpoints (#46), and JWT security with role checks (P2.1). The sections below describe the target design.

REST contract: [`contracts/openapi/leave.yaml`](../../contracts/openapi/leave.yaml).

## Command API (#44)
Writes only; GETs belong to the query role (#46). POST, PUT and PATCH answer `202 Accepted` with the resource, DELETEs answer an empty `202`; errors are problem+json (`400` invalid input, `404` unknown id, `409` business rule). Schema `V3__leave_domain.sql` and `V4__leave_ledger.sql`; the `employee_replica` is checked, never another service.

| Endpoint | Rules | Event |
|---|---|---|
| `POST /v1/leavetypes` (`Idempotency-Key`) | name unique ignoring case (`409`); `requiresApproval` and `isPaidLeave` default to `true` | none (not in the catalog) |
| `PUT /v1/leavetypes/{id}` | same validation; `404` when missing | none |
| `DELETE /v1/leavetypes/{id}` | hard delete; `409` while any leave request or ledger entry uses the type | none |
| `POST /v1/leaverequests` (`Idempotency-Key`) | `endDate >= startDate` (`400`); leave type must exist (`400`); employee must be active in the replica (`409`, the replica may lag); no overlap with the employee's PENDING / APPROVAL_PENDING / APPROVED requests (`409`). `endDate` is the last day of leave, so 1st-5th and 5th-7th overlap. Always starts `PENDING`, even when the type does not require approval (auto-approval is out of scope) | `leave.request.submitted.v1` |
| `PATCH /v1/leaverequests/{id}` | only while `PENDING` (`409`); optional `leaveTypeId`, `startDate`, `endDate`, `reason`; the same field validations as POST (`endDate >= startDate`, leave type must exist); the employee is not re-checked, since `employeeId` cannot change; overlap ignores itself; a PATCH that changes nothing returns the current request (`202`, no version bump, no event); no `Idempotency-Key` (as in scheduling's PUT) | `leave.request.submitted.v1` again, with a higher `aggregateVersion` |
| `DELETE /v1/leaverequests/{id}` | only `PENDING` becomes `CANCELLED` (the row stays); a repeated DELETE on a `CANCELLED` request is an idempotent `202` no-op (no change, no second event); any other status is `409` (#47 adds APPROVED with a REVERSAL ledger entry) | `leave.request.cancelled.v1` (first cancel only) |
| `POST /v1/leaveapprovals` (`Idempotency-Key`) | approver must be active in the replica (`409`), unknown `leaveRequestId` is `404`. `REJECTED`: request must be `PENDING` (`409`), becomes `REJECTED` with `rejectionReason` = comment (default "Rejected by manager") | `leave.request.rejected.v1` with the `decision` object |
| | **`APPROVED` is a placeholder:** `409` "Approval not available yet" (the leave-approval saga is not implemented, P3.1); no state change, no event. `LeaveRequest.startApproval` is the one place #47 replaces | none |
| `PUT /v1/leaveapprovals/{id}` | every stored decision is final today, so `404` when missing, otherwise `409` | none |
| `POST /v1/leaveledgers` (`Idempotency-Key`) | manual `ACCRUAL` (amount > 0) or `ADJUSTMENT` (amount ≠ 0, either sign) only; `RESERVATION`/`REVERSAL` by hand is `400`. At most 2 decimals and at most 99999.99 (`400`); leave type must exist (`400`); employee must be active in the replica (`409`); an entry that would make the balance of that employee and leave type negative is `409` "Insufficient leave balance" and writes nothing. `referenceEntityType`/`referenceEntityId` are optional and stored as given. No PUT, PATCH or DELETE exists (append-only) | `leave.ledger.entry-recorded.v1` |

`aggregateVersion` starts at 1 and increments on every change of a request. `DELETE /v1/leaveapprovals/{id}` is not implemented. Role checks (Administrator/Manager) wait for the JWT work (P2.1). 

### Concurrency
- **Edit, cancel and reject** lock the `leave_request` row (JPA `PESSIMISTIC_WRITE`, `SELECT … FOR NO KEY UPDATE` on PostgreSQL), so concurrent changes of one request run one after the other and the later one sees the committed state (a reject racing a cancel gets `409`).
- **Overlap rule:** `submit` and `edit` take a per-employee advisory lock (`pg_advisory_xact_lock(employeeId)`) before checking the employee's other requests, so two overlapping requests of one employee cannot both pass the check.
- **`Idempotency-Key`:** `IdempotencyService.execute` is one transaction that first takes an advisory lock on the (canonical, lower-case) key (`pg_advisory_xact_lock(4401, hash(key))`), so two simultaneous calls with the same key run the action once and the second one replays the first response. A failing command rolls back its effects together with the idempotency record, so failures are not stored and a retry with the same key runs again.
- **Bounded waits:** every lock wait is limited by `lock_timeout` (`DB_LOCK_TIMEOUT`, default `5s`, set per connection through Hikari's `connection-init-sql`), so a stuck lock cannot starve the connection pool. A timed-out wait or a deadlock is answered `409` "Conflicting change" and the client may retry; a retry with the same `Idempotency-Key` that waits longer than the timeout also gets that `409`. Flyway shares the datasource, so a blocked migration fails the boot after the timeout instead of hanging.
- **Ledger appends** (manual, `reserve`, `reverseReservation`) take the same per-employee advisory lock *before* they read the balance, so entry numbers have no gaps or duplicates and parallel reservations cannot overspend. The lock order stays idempotency key, then request row, then employee; nothing else is locked. `UNIQUE (employee_id, leave_type_id, entry_number)` is the backstop.
- **Backstop:** the exclusion constraint (`btree_gist`) still guards the overlap rule in the database. A lost lock race or deadlock (`ConcurrencyFailureException`) and a constraint violation are answered as `409` "Conflicting change" problem+json; the client may retry.

## Immutable ledger + snapshot
Ledger rows are never updated or deleted, in the application **and** in the database (`V4__leave_ledger.sql`: a trigger refuses `UPDATE`, `DELETE` and `TRUNCATE` on `leave_ledger` and `balance_snapshot`). A correction or saga compensation is a new entry.

- **Balance** = the sum of all entries of one *(employee, leave type)*; every leave type is tracked. Entries are numbered 1, 2, 3... per pair and carry `balance_after_days`; amounts are `NUMERIC` with at most 2 decimals.
- **Types:** `ACCRUAL` (+), `ADJUSTMENT` (±, never 0), `RESERVATION` (−, days of a leave request), `REVERSAL` (+, undoes a reservation). Only the first two can be posted by hand.
- **Days of a request** = calendar days, both ends included (`endDate − startDate + 1`; weekends count; `LeaveRequest.days()`).
- **Snapshots:** after every 50th entry of a pair the same transaction stores a `balance_snapshot`. The balance is always read as *newest snapshot + the entries after it* (at most 49 rows), never by summing the whole ledger.
- **`reserve(leaveRequestId, correlationId, causationId)`** (used by the saga, #47): locks the request row itself (`SELECT ... FOR UPDATE`, re-entrant when the caller already holds it), then the employee, and reads the ledger only after both locks. The caller must not already hold the employee lock. An unknown id is `LeaveRequestNotFoundException` (404). It needs balance >= days, else `InsufficientLeaveBalanceException` (409), then appends a `RESERVATION` of -days with `referenceEntityType = LEAVE_REQUEST` and `referenceEntityId` = the request. A request is reserved once (a second `reserve` is `409`; a partial unique index backs it). Both refusals are `noRollbackFor`, so a caller that catches them can still commit its own transaction.
- **`reverseReservation(leaveRequestId, correlationId, causationId)`**: same lock order (request row, then employee, lookups afterwards), so it sees a reservation another transaction has just committed. Appends a `REVERSAL` of the opposite amount (same reference). Idempotent: it returns empty and appends nothing when the id is unknown, the request has no reservation or it was already reversed.
- `causationId` (nullable) is the `eventId` that caused the step and goes into the envelope of the `entry-recorded` event; hand-posted entries have none.
- `reserve` and `reverseReservation` exist and are tested, but **no endpoint calls them yet**: `APPROVED` still answers `409` until the saga (#47) wires them in. Submitting does not check the balance.
- Every append publishes `leave.ledger.entry-recorded.v1` (`aggregateId` = `leaveLedgerId`, `aggregateVersion` 1, `data` as in `ledger-entry.schema.json`) through the outbox in the same transaction.
- A leave type with ledger entries cannot be deleted (`409`; the foreign key backs it up).

## Leave-approval saga (orchestrator)
See [`catalog.md`](../../contracts/events/catalog.md#flow-leave-approval-saga-orchestrated-by-leave-command).

| Step | On | Action |
|---|---|---|
| 1 | Manager approves | The request becomes `APPROVAL_PENDING`, a `RESERVATION` entry is appended, a saga row with a deadline is created, and it publishes `leave.request.approval-started.v1` |
| 2a | `scheduling.leave-release.completed.v1` | The request becomes `APPROVED` and it publishes `leave.request.approved.v1` |
| 2b | `scheduling.leave-release.rejected.v1` or the deadline passes | **Compensate:** append a `REVERSAL` entry, set the request to `REJECTED` with a reason, and publish `leave.request.rejected.v1` |

## Publishes (outbox)
Today: `leave.ledger.entry-recorded.v1` (every ledger append), `leave.request.submitted.v1` (submit and every changing edit), `leave.request.cancelled.v1` (cancel of a `PENDING` request; a repeated DELETE is a `202` no-op and publishes nothing) and `leave.request.rejected.v1`.
Planned: `leave.request.approval-started.v1` and `leave.request.approved.v1` (#47).

The relay polls the outbox every `OUTBOX_RELAY_DELAY` ms (default `2000`).

## Configuration
Environment variables only (names as in `docker-compose.microservices.yml`). Besides the usual `DB_*` and `RABBITMQ_*` variables: `DB_LOCK_TIMEOUT` (PostgreSQL `lock_timeout` per connection, default `5s`; keep it well below Hikari's 30 s connection timeout) and `OUTBOX_RELAY_DELAY`.

## Consumes
| Queue | Role | Routing keys |
|---|---|---|
| `leave.command` | command | exact keys `workforce.employee.created.v1`, `workforce.employee.updated.v1`, `workforce.employee.deleted.v1` (local employee replica for validating requests); `scheduling.leave-release.{completed,rejected}.v1` (saga replies) come with #47 |
| `leave.projection` | query | `leave.*`, `workforce.employee.*` |

A message that fails three times (or cannot be converted) is republished to the dead-letter exchange `shift-happens.dlx` with routing key `leave.command` (the queue name) and lands in `leave.command.dlq`; it is never requeued forever.

> **Upgrading a local broker:** the arguments (`x-dead-letter-routing-key`) and bindings of `leave.command` changed. RabbitMQ refuses to redeclare a queue with different arguments, so delete the existing `leave.command` **and** `leave.command.dlq` queues (RabbitAdmin never removes the old `#` binding of the DLQ; or run `docker compose -f docker-compose.microservices.yml down -v`) before starting the service.

## Extract from monolith
`leavetype`, `leaverequest`, `leaveapproval`, `leaveledger` and `view/employeeleaveoverview`. Remove PUT/DELETE on the ledger.

## Done when
- [ ] Both saga outcomes are covered by a cooperation test (Leave + Scheduling + RabbitMQ in Testcontainers).
- [ ] The balance in the read model equals the sum of the ledger after replay in any order.
