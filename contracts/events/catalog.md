# Event Catalog

All events are published to the topic exchange `shift-happens.events` with **routing key = `eventType`**. Every message body is an envelope (`envelope.schema.json`) with a payload in `data`.

## Events

| eventType | Producer | Published when | `data` schema |
|---|---|---|---|
| `workforce.employee.created.v1` | workforce | An employee is created | `payloads/employee.schema.json` |
| `workforce.employee.updated.v1` | workforce | Any employee field changes, including deactivation | `payloads/employee.schema.json` |
| `scheduling.shift.created.v1` | scheduling | A shift is created | `payloads/shift.schema.json` |
| `scheduling.shift.updated.v1` | scheduling | A shift or its required job roles change | `payloads/shift.schema.json` |
| `scheduling.shift.cancelled.v1` | scheduling | A shift is cancelled or deleted | `payloads/shift.schema.json` |
| `scheduling.assignment.created.v1` | scheduling | An employee is assigned to a shift | `payloads/assignment.schema.json` |
| `scheduling.assignment.updated.v1` | scheduling | Status, check-in or check-out changes, or a leave conflict or swap changes the assignment | `payloads/assignment.schema.json` |
| `scheduling.swap.requested.v1` | scheduling | An employee asks to hand an assignment to a colleague | `payloads/swap.schema.json` |
| `scheduling.swap.approved.v1` | scheduling | A manager approves the swap. The reassignment is committed in the same transaction and emitted as `assignment.updated` | `payloads/swap.schema.json` |
| `scheduling.swap.rejected.v1` | scheduling | A manager rejects the swap | `payloads/swap.schema.json` |
| `leave.request.submitted.v1` | leave | An employee submits a leave request | `payloads/leave-request.schema.json` |
| `leave.request.approved.v1` | leave | A manager approves it (the ledger usage entry is written in the same transaction) | `payloads/leave-request.schema.json` |
| `leave.request.rejected.v1` | leave | A manager rejects it | `payloads/leave-request.schema.json` |
| `leave.ledger.entry-recorded.v1` | leave | Any ledger entry is appended (accrual, usage, adjustment) | `payloads/ledger-entry.schema.json` |

## Queues and bindings

Each consuming service declares its own durable queue at startup. The queue name matches `RABBITMQ_QUEUE` in `docker-compose.microservices.yml`. In the binding patterns, `*` matches exactly one word (the version) and `#` matches zero or more words.

| Queue | Consumer | Bindings | What it does |
|---|---|---|---|
| `identity.accounts` | identity-service | `workforce.employee.#` | Creates or updates the login account (email, role, active flag) |
| `scheduling.projection` | scheduling-query | `scheduling.#`, `workforce.employee.#` | Builds the Mongo read model, including employee names |
| `scheduling.leave-conflicts` | scheduling-command | `leave.request.approved.*` | Marks overlapping assignments `LEAVE_CONFLICT` and emits `scheduling.assignment.updated.v1` |
| `leave.projection` | leave-query | `leave.#`, `workforce.employee.#` | Builds the Mongo read model: balances and overviews |
| `notification.events` | notification-service | `scheduling.assignment.created.*`, `scheduling.shift.cancelled.*`, `scheduling.swap.#`, `leave.request.#` | Stores in-app notifications and sends email |
| `audit.events` | audit-service | `#` | Appends every envelope unchanged |

Every queue has `x-dead-letter-exchange = shift-happens.dlx`. A consumer retries a message 3 times, then rejects it without requeue so it lands in the dead-letter queue.

## Example flow: shift swap

1. Browser `POST /api/shiftswaps` → scheduling-command. It checks via `GET workforce/employees/{employeeToId}` that the colleague is active, then saves `PENDING` and emits `scheduling.swap.requested.v1`.
2. notification-service emails the manager, and scheduling-query adds the swap to the read model.
3. Manager `POST /api/shiftswapapprovals {decision: APPROVED}` → scheduling-command. In **one transaction** it saves the approval, moves the assignment to `employeeToId` and writes both events to the outbox:
   - `scheduling.swap.approved.v1`
   - `scheduling.assignment.updated.v1`
4. notification-service emails both employees, scheduling-query updates the schedule, and audit-service logs both events.

## Example flow: approved leave conflicts with a shift

1. Manager approves leave → leave-command emits `leave.request.approved.v1` and `leave.ledger.entry-recorded.v1`.
2. scheduling-command (queue `scheduling.leave-conflicts`) finds that employee's assignments between `startDate` and `endDate`, marks them `LEAVE_CONFLICT` and emits `scheduling.assignment.updated.v1`.
3. notification-service tells the manager that the shift is now under-staffed.
