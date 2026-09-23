# leave-service

**Owns:** leave types, leave requests, leave approvals, the **immutable leave ledger** and the leave-approval **saga**.
**Deployed as two CQRS roles from one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | API |
|---|---|---|---|
| `command` | `leave-command` | `leave-write-db` (Postgres, Flyway) + outbox + saga table + employee replica | REST POST/PUT/PATCH/DELETE |
| `query` | `leave-query` | `leave-read-db` (MongoDB) | REST GET (balances, overviews) |

REST contract: [`contracts/openapi/leave.yaml`](../../contracts/openapi/leave.yaml).

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
`leave.request.{submitted,approval-started,approved,rejected}.v1` and `leave.ledger.entry-recorded.v1`.

## Consumes
| Queue | Role | Routing keys |
|---|---|---|
| `leave.command` | command | `scheduling.leave-release.{completed,rejected}.v1` (saga replies) and `workforce.employee.*` (local employee replica for validating requests) |
| `leave.projection` | query | `leave.*`, `workforce.employee.*` |

## Extract from monolith
`leavetype`, `leaverequest`, `leaveapproval`, `leaveledger` and `view/employeeleaveoverview`. Remove PUT/DELETE on the ledger.

## Done when
- [ ] Both saga outcomes are covered by a cooperation test (Leave + Scheduling + RabbitMQ in Testcontainers).
- [ ] The balance in the read model equals the sum of the ledger after replay in any order.
