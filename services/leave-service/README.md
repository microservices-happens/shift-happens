# leave-service

**Owns:** leave types, leave requests, leave approvals and the **immutable leave ledger**.
**Deployed as two CQRS roles from this one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | Handles |
|---|---|---|---|
| `command` | `leave-command` | `leave-write-db` (Postgres) + outbox | POST/PUT/PATCH/DELETE |
| `query` | `leave-query` | `leave-read-db` (Mongo) | GET, projection (balances, overviews) |

## Provides
- REST: [`contracts/openapi/leave.yaml`](../../contracts/openapi/leave.yaml)
  - `/leavetypes`, `/leaverequests`, `/leaveapprovals`
  - `/leaveledgers` (GET + POST only: append-only)
  - `/views/employee-leave-overview`

## Calls (sync REST)
- workforce `GET /employees/{id}`: the employee exists and is active before a request is accepted.

## Publishes
`leave.request.{submitted,approved,rejected}.v1` and `leave.ledger.entry-recorded.v1`. An approval writes the approval, the USAGE ledger entry and both events in one transaction.

## Consumes
| Queue | Role | Binding | Handling |
|---|---|---|---|
| `leave.projection` | query | `leave.#`, `workforce.employee.#` | Upsert overviews and balances |

## Extract from monolith
`leavetype`, `leaverequest`, `leaveapproval`, `leaveledger` and `view/employeeleaveoverview`. Remove PUT/DELETE on the ledger.

## Done when
- [ ] Leave pages work through the gateway.
- [ ] Approving leave updates the balance in the read model and triggers scheduling's leave-conflict check.
