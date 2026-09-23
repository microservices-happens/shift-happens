# scheduling-service

**Owns:** shifts, required job roles per shift, shift assignments, shift approvals, **shift swaps** and swap approvals.
**Deployed as two CQRS roles from this one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | Handles |
|---|---|---|---|
| `command` | `scheduling-command` | `scheduling-write-db` (Postgres) + outbox | POST/PUT/DELETE, leave conflicts |
| `query` | `scheduling-query` | `scheduling-read-db` (Mongo) | GET, projection |

Shift swaps used to be a separate service. They live here because a swap only reassigns an existing assignment. Approving a swap and moving the assignment happen in **one local transaction**, so no saga or compensation is needed.

## Provides
- REST: [`contracts/openapi/scheduling.yaml`](../../contracts/openapi/scheduling.yaml)
  - `/shifts`, `/shiftrequiredjobroles`, `/shiftassignments`, `/shiftapprovals`, `/shiftswaps`, `/shiftswapapprovals`
  - `/views/employee-shift-overview`

## Calls (sync REST)
- workforce `GET /employees/{id}`: the employee exists and is active (assign, swap target).
- workforce `GET /employeejobroles?employeeId=`: the employee has the job role the shift requires.

## Publishes
`scheduling.shift.{created,updated,cancelled}.v1`, `scheduling.assignment.{created,updated}.v1`, `scheduling.swap.{requested,approved,rejected}.v1`. The payload schemas are listed in [`catalog.md`](../../contracts/events/catalog.md).

## Consumes
| Queue | Role | Binding | Handling |
|---|---|---|---|
| `scheduling.projection` | query | `scheduling.#`, `workforce.employee.#` | Upsert read-model documents (ignore older `aggregateVersion`) |
| `scheduling.leave-conflicts` | command | `leave.request.approved.*` | Mark overlapping assignments `LEAVE_CONFLICT` and publish `assignment.updated` |

## Extract from monolith
`shift`, `shiftrequiredjobrole`, `shiftassignment`, `shiftapproval`, `shiftswap`, `shiftswapapproval` and `view/employeeshiftoverview`.

## Done when
- [ ] Shift and swap pages work through the gateway.
- [ ] An approved swap moves the assignment and both events reach notification and audit.
- [ ] Approved leave flags the overlapping assignment.
