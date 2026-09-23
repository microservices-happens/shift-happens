# Event Catalog

Generated from the same table as [`../asyncapi.yaml`](../asyncapi.yaml), which is the formal contract. All events go to the topic exchange `shift-happens.events` with **routing key = `eventType`**. Every message body is an envelope (`envelope.schema.json`) with the payload in `data`.

## Events

| eventType | Producer | Published when | `data` schema | Consumed by (queue) |
|---|---|---|---|---|
| `workforce.employee.created.v1` | workforce-service | Employee created | `payloads/employee.schema.json` | `identity.accounts`, `scheduling.command`, `scheduling.projection`, `leave.command`, `leave.projection`, `audit.events` |
| `workforce.employee.updated.v1` | workforce-service | Any employee field or job role changes | `payloads/employee.schema.json` | `identity.accounts`, `scheduling.command`, `scheduling.projection`, `leave.command`, `leave.projection`, `audit.events` |
| `workforce.employee.deleted.v1` | workforce-service | Employee deleted (tombstone) | `payloads/tombstone.schema.json` | `identity.accounts`, `scheduling.command`, `scheduling.projection`, `leave.command`, `leave.projection`, `audit.events` |
| `scheduling.shift.created.v1` | scheduling-service | Shift created | `payloads/shift.schema.json` | `scheduling.projection`, `audit.events` |
| `scheduling.shift.updated.v1` | scheduling-service | Shift, its required roles or its status (incl. CANCELLED) changes | `payloads/shift.schema.json` | `scheduling.projection`, `notification.events`, `audit.events` |
| `scheduling.shift.deleted.v1` | scheduling-service | Shift deleted (tombstone) | `payloads/tombstone.schema.json` | `scheduling.projection`, `audit.events` |
| `scheduling.shift.understaffed.v1` | scheduling-service | A released or cancelled assignment leaves a shift short of a job role | `payloads/understaffed.schema.json` | `ai.suggestions`, `notification.events`, `audit.events` |
| `scheduling.assignment.created.v1` | scheduling-service | Employee assigned to a shift | `payloads/assignment.schema.json` | `scheduling.projection`, `notification.events`, `audit.events` |
| `scheduling.assignment.updated.v1` | scheduling-service | Status, check-in/out, swap or leave release changes an assignment | `payloads/assignment.schema.json` | `scheduling.projection`, `notification.events`, `audit.events` |
| `scheduling.assignment.deleted.v1` | scheduling-service | Assignment deleted (tombstone) | `payloads/tombstone.schema.json` | `scheduling.projection`, `audit.events` |
| `scheduling.swap.requested.v1` | scheduling-service | Employee asks to hand an assignment to a colleague | `payloads/swap.schema.json` | `scheduling.projection`, `notification.events`, `audit.events` |
| `scheduling.swap.approved.v1` | scheduling-service | Manager approves; reassignment committed in the same local transaction | `payloads/swap.schema.json` | `scheduling.projection`, `notification.events`, `audit.events` |
| `scheduling.swap.rejected.v1` | scheduling-service | Manager rejects the swap | `payloads/swap.schema.json` | `scheduling.projection`, `notification.events`, `audit.events` |
| `scheduling.leave-release.completed.v1` | scheduling-service | Saga reply: overlapping assignments released | `payloads/leave-release.schema.json` | `leave.command`, `audit.events` |
| `scheduling.leave-release.rejected.v1` | scheduling-service | Saga reply: release refused (e.g. a locked shift); nothing changed | `payloads/leave-release.schema.json` | `leave.command`, `audit.events` |
| `leave.request.submitted.v1` | leave-service | Employee submits a leave request | `payloads/leave-request.schema.json` | `leave.projection`, `notification.events`, `audit.events` |
| `leave.request.approval-started.v1` | leave-service | Saga start: manager approved, days reserved in the ledger | `payloads/leave-request.schema.json` | `scheduling.command`, `leave.projection`, `audit.events` |
| `leave.request.approved.v1` | leave-service | Saga completed | `payloads/leave-request.schema.json` | `leave.projection`, `notification.events`, `audit.events` |
| `leave.request.rejected.v1` | leave-service | Manager rejects, or saga compensated (reason in rejectionReason) | `payloads/leave-request.schema.json` | `leave.projection`, `notification.events`, `audit.events` |
| `leave.ledger.entry-recorded.v1` | leave-service | Any ledger entry appended (accrual, reservation, reversal, adjustment) | `payloads/ledger-entry.schema.json` | `leave.projection`, `audit.events` |
| `ai.replacement.suggested.v1` | ai-service | Ranked replacement candidates for an understaffed shift | `payloads/replacement-suggestion.schema.json` | `scheduling.projection`, `notification.events`, `audit.events` |
| `notification.email.requested.v1` | notification-service | A notification should also be emailed | `payloads/email-request.schema.json` | `email.send` |

## Queues

Each queue is declared by its consumer at startup, bound to the exact routing keys below, and has `x-dead-letter-exchange = shift-happens.dlx`. The queue name matches `RABBITMQ_QUEUE` in `docker-compose.microservices.yml`.

| Queue | Consumer | Binds routing keys | Purpose |
|---|---|---|---|
| `identity.accounts` | identity-service | `workforce.employee.created.v1`<br>`workforce.employee.updated.v1`<br>`workforce.employee.deleted.v1` | Upsert the login account (email, role, active); tombstone disables it |
| `scheduling.command` | scheduling-command | `workforce.employee.created.v1`<br>`workforce.employee.updated.v1`<br>`workforce.employee.deleted.v1`<br>`leave.request.approval-started.v1` | Keeps a local employee replica for validation; saga participant for leave release |
| `scheduling.projection` | scheduling-query | `workforce.employee.created.v1`<br>`workforce.employee.updated.v1`<br>`workforce.employee.deleted.v1`<br>`scheduling.shift.created.v1`<br>`scheduling.shift.updated.v1`<br>`scheduling.shift.deleted.v1`<br>`scheduling.assignment.created.v1`<br>`scheduling.assignment.updated.v1`<br>`scheduling.assignment.deleted.v1`<br>`scheduling.swap.requested.v1`<br>`scheduling.swap.approved.v1`<br>`scheduling.swap.rejected.v1`<br>`ai.replacement.suggested.v1` | Builds the Mongo read model served over GraphQL |
| `leave.command` | leave-command | `workforce.employee.created.v1`<br>`workforce.employee.updated.v1`<br>`workforce.employee.deleted.v1`<br>`scheduling.leave-release.completed.v1`<br>`scheduling.leave-release.rejected.v1` | Saga orchestrator (completes or compensates leave approvals); keeps a local employee replica |
| `leave.projection` | leave-query | `workforce.employee.created.v1`<br>`workforce.employee.updated.v1`<br>`workforce.employee.deleted.v1`<br>`leave.request.submitted.v1`<br>`leave.request.approval-started.v1`<br>`leave.request.approved.v1`<br>`leave.request.rejected.v1`<br>`leave.ledger.entry-recorded.v1` | Builds the Mongo read model: overviews and balances |
| `notification.events` | notification-service | `scheduling.shift.updated.v1`<br>`scheduling.shift.understaffed.v1`<br>`scheduling.assignment.created.v1`<br>`scheduling.assignment.updated.v1`<br>`scheduling.swap.requested.v1`<br>`scheduling.swap.approved.v1`<br>`scheduling.swap.rejected.v1`<br>`leave.request.submitted.v1`<br>`leave.request.approved.v1`<br>`leave.request.rejected.v1`<br>`ai.replacement.suggested.v1` | Stores in-app notifications and requests emails |
| `ai.suggestions` | ai-service | `scheduling.shift.understaffed.v1` | Ranks replacement candidates with the LLM |
| `audit.events` | audit-service | `workforce.employee.created.v1`<br>`workforce.employee.updated.v1`<br>`workforce.employee.deleted.v1`<br>`scheduling.shift.created.v1`<br>`scheduling.shift.updated.v1`<br>`scheduling.shift.deleted.v1`<br>`scheduling.shift.understaffed.v1`<br>`scheduling.assignment.created.v1`<br>`scheduling.assignment.updated.v1`<br>`scheduling.assignment.deleted.v1`<br>`scheduling.swap.requested.v1`<br>`scheduling.swap.approved.v1`<br>`scheduling.swap.rejected.v1`<br>`scheduling.leave-release.completed.v1`<br>`scheduling.leave-release.rejected.v1`<br>`leave.request.submitted.v1`<br>`leave.request.approval-started.v1`<br>`leave.request.approved.v1`<br>`leave.request.rejected.v1`<br>`leave.ledger.entry-recorded.v1`<br>`ai.replacement.suggested.v1` | Appends every envelope unchanged |
| `email.send` | email-function | `notification.email.requested.v1` | Sends one email per message (serverless, KEDA ScaledJob) |

## Flow: leave-approval saga (orchestrated by leave-command)

Consistency across Leave and Scheduling uses compensating actions, not a distributed transaction.

1. **Manager approves.** `POST /api/v1/leaveapprovals {decision: APPROVED}` reaches leave-command. In one local transaction it:
   - sets the request to `APPROVAL_PENDING`
   - appends a `RESERVATION` ledger entry (−days)
   - starts a saga row with a deadline
   - writes `leave.request.approval-started.v1` and `leave.ledger.entry-recorded.v1` to the outbox
2. **Scheduling releases the employee.** scheduling-command (queue `scheduling.command`) finds that employee's assignments between `startDate` and `endDate`.
   - **All can be released:** it marks them `RELEASED_FOR_LEAVE` and publishes:
     - `scheduling.leave-release.completed.v1`
     - `scheduling.assignment.updated.v1` for each released assignment
     - `scheduling.shift.understaffed.v1` for each shift that is now short
   - **Any is `LOCKED`** (starts within 24 h, or has been checked into): it changes nothing and publishes `scheduling.leave-release.rejected.v1` with a reason.
3. **Leave completes or compensates.** leave-command (queue `leave.command`) handles the reply:
   - `completed`: the request becomes `APPROVED` and it publishes `leave.request.approved.v1`.
   - `rejected`, or the deadline passes with no reply: **compensation**. It appends a `REVERSAL` ledger entry (+days, never an update), sets the request to `REJECTED` with `rejectionReason` and publishes `leave.request.rejected.v1`.
   - If a late `completed` arrives after a timeout compensation, leave-command publishes a new `leave.request.approval-started.v1` retry instead of silently approving.
4. **Notification** tells the employee the outcome. **Audit** records every step under one `correlationId`.

## Flow: AI replacement suggestion

1. scheduling-command publishes `scheduling.shift.understaffed.v1` (after a leave release or a cancelled assignment). The event already lists qualified, active candidates who are free at that time.
2. ai-service (queue `ai.suggestions`) sends the shift and candidates to the LLM (Ollama locally) and asks for a ranking with a one-line reason per candidate. If the LLM is slow or down, it ranks by fewest hours this week and marks `source: rule`.
3. ai-service publishes `ai.replacement.suggested.v1`.
4. scheduling-query stores it on the shift, so the frontend sees it in GraphQL (`Shift.replacementSuggestions`). notification-service alerts the manager.
5. The manager picks someone with `POST /api/v1/shiftassignments`. The AI only advises; it never writes the schedule.

## Flow: shift swap (single service, no saga needed)

1. `POST /api/v1/shiftswaps` → scheduling-command checks the colleague against its local employee replica (active, has the job role), saves `PENDING` and publishes `scheduling.swap.requested.v1`.
2. `POST /api/v1/shiftswapapprovals {decision: APPROVED}` → in **one local transaction** it saves the approval, moves the assignment and publishes `scheduling.swap.approved.v1` and `scheduling.assignment.updated.v1`.

## Flow: email (serverless)

notification-service stores the in-app notification and publishes `notification.email.requested.v1`. email-function (queue `email.send`) sends it over SMTP. In Kubernetes, KEDA starts a Job only while the queue has messages.

