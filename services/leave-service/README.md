# leave-service

**Owns:** leave types, leave requests, leave approvals, the **immutable leave ledger** and the leave-approval **saga**.
**Deployed as two CQRS roles from one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | API |
|---|---|---|---|
| `command` | `leave-command` | `leave-write-db` (Postgres, Flyway) + outbox + employee replica + saga table | REST POST/PUT/PATCH/DELETE |
| `query` | `leave-query` | `leave-read-db` (MongoDB) | REST GET (balances, overviews) |

> **Status:** the command side has its foundation (transactional outbox + relay, DLX topology, `processed_events`, `Idempotency-Key`, RFC 9457 problem+json, the employee replica fed by `workforce.employee.*`, the `leave.command` queue) and, since #44, the **write API for leave types, leave requests and leave approvals** (see "Command API (#44)") and, since #45, the **immutable ledger with balance snapshots** and `POST /v1/leaveledgers` (see "Immutable ledger + snapshot"). Since #47 / P3.1 the **leave-approval saga** is live: `APPROVED` decisions start it, Scheduling's replies complete or compensate it, a timeout compensates it, and approved leave can be cancelled (see "Leave-approval saga"). Still missing: the query role and all GET endpoints (#46), and JWT security with role checks (P2.1). The sections below describe the target design.

REST contract: [`contracts/openapi/leave.yaml`](../../contracts/openapi/leave.yaml).

## Command API (#44)
Writes only; GETs belong to the query role (#46). POST, PUT and PATCH answer `202 Accepted` with the resource, DELETEs answer an empty `202`; errors are problem+json (`400` invalid input, `404` unknown id, `409` business rule). Schema `V3__leave_domain.sql`, `V4__leave_ledger.sql` and `V5__leave_approval_saga.sql`; the `employee_replica` is checked, never another service.

| Endpoint | Rules | Event |
|---|---|---|
| `POST /v1/leavetypes` (`Idempotency-Key`) | name unique ignoring case (`409`); `requiresApproval` and `isPaidLeave` default to `true` | none (not in the catalog) |
| `PUT /v1/leavetypes/{id}` | same validation; `404` when missing | none |
| `DELETE /v1/leavetypes/{id}` | hard delete; `409` while any leave request or ledger entry uses the type | none |
| `POST /v1/leaverequests` (`Idempotency-Key`) | `endDate >= startDate` (`400`); leave type must exist (`400`); employee must be active in the replica (`409`, the replica may lag); no overlap with the employee's PENDING / APPROVAL_PENDING / APPROVED requests (`409`). `endDate` is the last day of leave, so 1st-5th and 5th-7th overlap. Always starts `PENDING`, even when the type does not require approval (auto-approval is out of scope) | `leave.request.submitted.v1` |
| `PATCH /v1/leaverequests/{id}` | only while `PENDING` (`409`); optional `leaveTypeId`, `startDate`, `endDate`, `reason`; the same field validations as POST (`endDate >= startDate`, leave type must exist); the employee is not re-checked, since `employeeId` cannot change; overlap ignores itself; a PATCH that changes nothing returns the current request (`202`, no version bump, no event); no `Idempotency-Key` (as in scheduling's PUT) | `leave.request.submitted.v1` again, with a higher `aggregateVersion` |
| `DELETE /v1/leaverequests/{id}` | `PENDING` becomes `CANCELLED` (the row stays). `APPROVED` becomes `CANCELLED` too, in the same transaction a `REVERSAL` ledger entry gives back the reservation of the saga attempt that completed. `APPROVAL_PENDING` is `409` (the saga is in flight; wait for the outcome), `REJECTED` is `409`. A repeated DELETE on a `CANCELLED` request is an idempotent `202` no-op (no change, no second event, no second reversal) | `leave.request.cancelled.v1` (first cancel only; Scheduling drops the leave window of an approved one) |
| `POST /v1/leaveapprovals` (`Idempotency-Key`) | approver must be active in the replica (`409`), unknown `leaveRequestId` is `404`. `REJECTED`: request must be `PENDING` (`409`), becomes `REJECTED` with `rejectionReason` = comment (default "Rejected by manager") | `leave.request.rejected.v1` with the `decision` object |
| | **`APPROVED` starts the saga** (`202`; the final outcome arrives later): the request must be `PENDING` (else `409`, e.g. "leave request 7 is CANCELLED and cannot be approved") and its employee active in the replica (`409`). One transaction stores the approval, moves the request to `APPROVAL_PENDING`, reserves the days (every leave type needs a balance; too little is `409` "Insufficient leave balance" and **nothing** is written), stores saga attempt 1 with its deadline and publishes the events | `leave.request.approval-started.v1` (+ `leave.ledger.entry-recorded.v1` of the reservation) |
| `PUT /v1/leaveapprovals/{id}` | every stored decision is final (a `REJECTED` one is final, an `APPROVED` one belongs to a saga that cannot be redirected), so `404` when missing, otherwise `409` | none |
| `POST /v1/leaveledgers` (`Idempotency-Key`) | manual `ACCRUAL` (amount > 0) or `ADJUSTMENT` (amount ≠ 0, either sign) only; `RESERVATION`/`REVERSAL` by hand is `400`. At most 2 decimals and at most 99999.99 (`400`); leave type must exist (`400`); employee must be active in the replica (`409`); an entry that would make the balance of that employee and leave type negative is `409` "Insufficient leave balance" and writes nothing. `referenceEntityType`/`referenceEntityId` are optional and stored as given. No PUT, PATCH or DELETE exists (append-only) | `leave.ledger.entry-recorded.v1` |

`aggregateVersion` starts at 1 and increments on every change of a request. `DELETE /v1/leaveapprovals/{id}` is not implemented. Role checks (Administrator/Manager) wait for the JWT work (P2.1). 

### Concurrency
- **Edit, cancel and reject** lock the `leave_request` row (JPA `PESSIMISTIC_WRITE`, `SELECT … FOR NO KEY UPDATE` on PostgreSQL), so concurrent changes of one request run one after the other and the later one sees the committed state (a reject racing a cancel gets `409`).
- **Overlap rule:** `submit` and `edit` take a per-employee advisory lock (`pg_advisory_xact_lock(employeeId)`) before checking the employee's other requests, so two overlapping requests of one employee cannot both pass the check.
- **`Idempotency-Key`:** `IdempotencyService.execute` is one transaction that first takes an advisory lock on the (canonical, lower-case) key (`pg_advisory_xact_lock(4401, hash(key))`), so two simultaneous calls with the same key run the action once and the second one replays the first response. A failing command rolls back its effects together with the idempotency record, so failures are not stored and a retry with the same key runs again.
- **Bounded waits:** every lock wait is limited by `lock_timeout` (`DB_LOCK_TIMEOUT`, default `5s`, set per connection through Hikari's `connection-init-sql`), so a stuck lock cannot starve the connection pool. A timed-out wait or a deadlock is answered `409` "Conflicting change" and the client may retry; a retry with the same `Idempotency-Key` that waits longer than the timeout also gets that `409`. Flyway shares the datasource, so a blocked migration fails the boot after the timeout instead of hanging.
- **Saga paths keep the same lock order** (idempotency key, request row, employee): the approval, a reply, the timeout and a cancel all lock the request row first and re-read the saga attempt afterwards, so exactly one of them decides. The ledger steps take the employee lock themselves; nothing takes the employee lock before the request row. The timeout job uses `FOR UPDATE SKIP LOCKED` on the request row and simply tries again at the next poll when a reply holds it.
- **Ledger appends** (manual, `reserve`, `reverseReservation`) take the same per-employee advisory lock *before* they read the balance, so entry numbers have no gaps or duplicates and parallel reservations cannot overspend. The lock order stays idempotency key, then request row, then employee; nothing else is locked. `UNIQUE (employee_id, leave_type_id, entry_number)` is the backstop.
- **Backstop:** the exclusion constraint (`btree_gist`) still guards the overlap rule in the database. A lost lock race or deadlock (`ConcurrencyFailureException`) and a constraint violation are answered as `409` "Conflicting change" problem+json; the client may retry.

## Immutable ledger + snapshot
Ledger rows are never updated or deleted, in the application **and** in the database (`V4__leave_ledger.sql`: a trigger refuses `UPDATE`, `DELETE` and `TRUNCATE` on `leave_ledger` and `balance_snapshot`). A correction or saga compensation is a new entry.

- **Balance** = the sum of all entries of one *(employee, leave type)*; every leave type is tracked. Entries are numbered 1, 2, 3... per pair and carry `balance_after_days`; amounts are `NUMERIC` with at most 2 decimals.
- **Types:** `ACCRUAL` (+), `ADJUSTMENT` (±, never 0), `RESERVATION` (−, days of a leave request), `REVERSAL` (+, undoes a reservation). Only the first two can be posted by hand.
- **Days of a request** = calendar days, both ends included (`endDate − startDate + 1`; weekends count; `LeaveRequest.days()`).
- **Snapshots:** after every 50th entry of a pair the same transaction stores a `balance_snapshot`. The balance is always read as *newest snapshot + the entries after it* (at most 49 rows), never by summing the whole ledger.
- **`reserve(leaveRequestId, sagaAttempt, correlationId, causationId)`** (used by the saga): locks the request row itself (`SELECT ... FOR UPDATE`, re-entrant when the caller already holds it), then the employee, and reads the ledger only after both locks. The caller must not already hold the employee lock. An unknown id is `LeaveRequestNotFoundException` (404). It needs balance >= days, else `InsufficientLeaveBalanceException` (409), then appends a `RESERVATION` of -days with `referenceEntityType = LEAVE_REQUEST` and `referenceEntityId` = the request. A saga attempt is reserved once (a second `reserve` of the same attempt is `409`; a partial unique index on `(reference_entity_id, saga_attempt)` backs it). `leave_ledger.saga_attempt` (V5) is null for manual entries; a request has one reservation and one reversal **per saga attempt**, so a late `completed` reply after a timeout can reserve again. Both refusals are `noRollbackFor`, so a caller that catches them can still commit its own transaction.
- **`reverseReservation(leaveRequestId, sagaAttempt, correlationId, causationId)`**: same lock order (request row, then employee, lookups afterwards), so it sees a reservation another transaction has just committed. Appends a `REVERSAL` of the opposite amount (same reference). Idempotent: it returns empty and appends nothing when the id is unknown, the attempt has no reservation or it was already reversed.
- `causationId` (nullable) is the `eventId` that caused the step and goes into the envelope of the `entry-recorded` event; hand-posted entries have none.
- `reserve` and `reverseReservation` are called by the saga only (approve, reply, timeout, cancel of approved leave). Submitting does not check the balance; approving does.
- Every append publishes `leave.ledger.entry-recorded.v1` (`aggregateId` = `leaveLedgerId`, `aggregateVersion` 1, `data` as in `ledger-entry.schema.json`) through the outbox in the same transaction.
- A leave type with ledger entries cannot be deleted (`409`; the foreign key backs it up).

## Leave-approval saga (orchestrator)
See [`catalog.md`](../../contracts/events/catalog.md#flow-leave-approval-saga-orchestrated-by-leave-command). Scheduling's half (`LeaveReleaseService`) answers every `approval-started` with a stored reply and republishes the same reply when it sees the request again.

| Step | On | Action |
|---|---|---|
| 1 | Manager approves | One transaction under the request row lock: `APPROVAL_PENDING`, `RESERVATION` entry (attempt n), saga row `STARTED` with `deadline = now + SAGA_TIMEOUT`, outbox: `leave.request.approval-started.v1` + `leave.ledger.entry-recorded.v1` |
| 2a | `scheduling.leave-release.completed.v1` | The request becomes `APPROVED`, the saga `COMPLETED`; it publishes `leave.request.approved.v1` (`causationId` = the reply's `eventId`) |
| 2b | `scheduling.leave-release.rejected.v1` | **Compensate:** `REVERSAL` entry (`causationId` = the reply), the request becomes `REJECTED` with `rejectionReason` = the reply's `reason`, the saga `COMPENSATED`; it publishes `leave.request.rejected.v1` |
| 2c | the deadline passes with no reply | The same compensation with `rejectionReason` "Timed out waiting for Scheduling to release the shifts" and saga `TIMED_OUT` (no `causationId`: nothing caused it) |
| 3 | a late `completed` after 2c | **Retry, not silent approval** (catalog): `REJECTED` goes back to `APPROVAL_PENDING`, the days are reserved again as attempt n+1, a new `approval-started` is published (its `causationId` is the late reply) and a new saga row starts. Scheduling replays its stored `completed`, which then approves attempt n+1 |

- **Correlation:** a reply belongs to the saga attempt whose `approval_started_event_id` equals the reply's `causationId` (and whose request is the reply's `aggregateId`). A reply without `causationId` answers the attempt in flight; a reply with an unknown `causationId` or for an unknown request is logged and ignored.
- **What a reply does** (`LeaveApprovalSaga.reactTo`): attempt `STARTED`: completed approves, rejected compensates. `TIMED_OUT`: completed retries (only the newest attempt, only below the cap), rejected is ignored. `COMPLETED` and `COMPENSATED` are final: every reply is ignored. A retry does not happen when the balance no longer covers the days, or when the employee has since requested overlapping leave; the request then stays `REJECTED` (logged).
- **Retry cap:** at most **3 attempts** per request (`LeaveApprovalSaga.MAX_ATTEMPTS`: the first one and two retries). A late `completed` of the third attempt is ignored.
- **Idempotent consumer:** the reply's `eventId` goes into `processed_events` in the same transaction; the request row is locked first and the saga attempt is read after the lock. A malformed reply (no `leaveRequestId`, or one that differs from `aggregateId`) fails three times and is dead-lettered.
- **Timeout:** `SAGA_TIMEOUT` (ISO-8601 duration, default `PT5M`; already set in `docker-compose.microservices.yml` and `kubernetes/leave-command/deployment.yaml`) is the time Scheduling gets. `SAGA_TIMEOUT_POLL_DELAY` (milliseconds, default `15000`; not in Compose) is how often `SagaTimeoutJob` looks for overdue `STARTED` attempts. Per overdue attempt it opens its own transaction, locks the request row with `FOR UPDATE SKIP LOCKED` (a row a reply is holding is skipped until the next poll) and re-checks that the attempt is still `STARTED` and overdue, so a reply and the timeout cannot both decide.
- **Cancelling approved leave** (`DELETE /v1/leaverequests/{id}`): `APPROVED` becomes `CANCELLED`, the reservation of the completed attempt is reversed and `leave.request.cancelled.v1` is published. `APPROVAL_PENDING` cannot be cancelled (`409`).
- **State machine of a request:** `PENDING` -> `APPROVAL_PENDING` (approve) | `REJECTED` (manager) | `CANCELLED`; `APPROVAL_PENDING` -> `APPROVED` | `REJECTED`; `APPROVED` -> `CANCELLED`; `REJECTED` -> `APPROVAL_PENDING` only through the retry above.
- After a compensation the request keeps the manager's `decision` (`APPROVED`) in its snapshot; `requestStatus` and `rejectionReason` tell the outcome.

## Publishes (outbox)
`leave.ledger.entry-recorded.v1` (every ledger append), `leave.request.submitted.v1` (submit and every changing edit), `leave.request.approval-started.v1` (approve, and every retry), `leave.request.approved.v1` (saga completed), `leave.request.rejected.v1` (manager rejection or saga compensation) and `leave.request.cancelled.v1` (cancel of a `PENDING` or `APPROVED` request; a repeated DELETE is a `202` no-op and publishes nothing).

The relay polls the outbox every `OUTBOX_RELAY_DELAY` ms (default `2000`).

## Configuration
Environment variables only (names as in `docker-compose.microservices.yml`). Besides the usual `DB_*` and `RABBITMQ_*` variables: `DB_LOCK_TIMEOUT` (PostgreSQL `lock_timeout` per connection, default `5s`; keep it well below Hikari's 30 s connection timeout) `OUTBOX_RELAY_DELAY`, `SAGA_TIMEOUT` (default `PT5M`) and `SAGA_TIMEOUT_POLL_DELAY` (default `15000` ms).

## Consumes
| Queue | Role | Routing keys |
|---|---|---|
| `leave.command` | command | exact keys `workforce.employee.created.v1`, `workforce.employee.updated.v1`, `workforce.employee.deleted.v1` (local employee replica for validating requests); `scheduling.leave-release.completed.v1` and `scheduling.leave-release.rejected.v1` (the saga replies) |
| `leave.projection` | query | `leave.*`, `workforce.employee.*` |

A message that fails three times (or cannot be converted) is republished to the dead-letter exchange `shift-happens.dlx` with routing key `leave.command` (the queue name) and lands in `leave.command.dlq`; it is never requeued forever.

> **Upgrading a local broker:** the arguments (`x-dead-letter-routing-key`) and bindings of `leave.command` changed. RabbitMQ refuses to redeclare a queue with different arguments, so delete the existing `leave.command` **and** `leave.command.dlq` queues (RabbitAdmin never removes the old `#` binding of the DLQ; or run `docker compose -f docker-compose.microservices.yml down -v`) before starting the service.

## Extract from monolith
`leavetype`, `leaverequest`, `leaveapproval`, `leaveledger` and `view/employeeleaveoverview`. Remove PUT/DELETE on the ledger.

## Done when
- [x] Both saga outcomes are covered by a cooperation test (Leave + Scheduling + RabbitMQ in Testcontainers): `tests/cooperation/.../LeaveApprovalSagaIT`.
- [ ] The balance in the read model equals the sum of the ledger after replay in any order.
