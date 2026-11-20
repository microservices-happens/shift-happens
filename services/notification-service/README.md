# notification-service

**Owns:** in-app notifications and the e-mail requests they cause.
**Database:** `notification-db` (Postgres, Flyway). **Compose:** `notification-service`.
**Contract:** [`contracts/openapi/notification.yaml`](../../contracts/openapi/notification.yaml).

> **Status: implemented (P4.3).** Consumes `notification.events`, stores one notification per event and
> recipient, publishes `notification.email.requested.v1` through the transactional outbox, and serves the
> REST API with JWT validation. `NotificationPolicyTest` covers the rules; `NotificationServiceIT` covers the
> consumer → database → outbox → exchange path, ownership and the 401/403 matrix.

## Provides

| Endpoint | Who | Behaviour |
|---|---|---|
| `GET /v1/notifications?unreadOnly=` | any valid token | The caller's own notifications, newest first (`employeeId` comes from the JWT, never from the URL) |
| `PATCH /v1/notifications/{id}` `{ "read": true }` | the owner | Marks one notification read; another employee's notification is `403`, an unknown id `404` |
| `GET /health` | public | Compose/Kubernetes probe |

Errors are RFC 9457 `application/problem+json`. Swagger UI at `/swagger-ui.html` renders the hand-written
contract (copied onto the classpath at build time).

## Consumes (queue `notification.events`)

All routing keys of the queue in [`contracts/events/catalog.md`](../../contracts/events/catalog.md):

| Event | Recipient | Title |
|---|---|---|
| `workforce.employee.created/updated/deleted.v1` | — | Keeps the local employee replica (e-mail, role, active) |
| `scheduling.assignment.created.v1` | the assigned employee | New shift assignment |
| `scheduling.assignment.updated.v1` | the employee, only for `CANCELLED`, `RELEASED_FOR_LEAVE`, `SWAPPED` | Shift assignment updated |
| `scheduling.swap.requested.v1` | the colleague asked | Shift swap request |
| `scheduling.swap.approved.v1` | both parties | Shift swap approved |
| `scheduling.swap.rejected.v1` | the requester | Shift swap rejected (with the reason) |
| `leave.request.submitted.v1` | the employee | Leave request received |
| `leave.request.approved.v1` / `rejected.v1` | the employee | Leave approved / rejected |
| `scheduling.shift.understaffed.v1` | every active manager | Shift understaffed |
| `ai.replacement.suggested.v1` | every active manager | Replacement suggestion ready |
| `scheduling.shift.updated.v1` with `shiftStatus=CANCELLED` | every active manager | Shift cancelled |

Manager alerts fan out to every active `Manager` in the replica, because an employee snapshot carries the
role but not a manager-of-department relation and a synchronous call to workforce is forbidden. The report
lists this as a known trade-off.

## Publishes (outbox)

`notification.email.requested.v1` (`payloads/email-request.schema.json`) for every notification whose
recipient has an active e-mail address. `aggregateId` = `notificationId`, `causationId` = the source event.
The relay sends the row to `shift-happens.events`; email-function consumes queue `email.send`.

## Data (`V1__init.sql`)

| Table | Purpose |
|---|---|
| `processed_event` | Idempotent consumer: an `eventId` is stored in the same transaction as its notifications |
| `notification` | The in-app notifications; unique `(source_event_id, employee_id)` stops a duplicate even before the dedupe row is read |
| `employee_contact` | Version-aware replica of employee e-mail/role/status; highest `aggregate_version` wins and a tombstone cannot be resurrected |
| `outbox_event` | Transactional outbox for the e-mail requests |

## Run and test

```bash
mvn -B -f services/notification-service/pom.xml test      # NotificationPolicyTest + SwaggerContractTest
mvn -B -f services/notification-service/pom.xml verify    # + NotificationServiceIT (Postgres + RabbitMQ)
```

`NotificationServiceIT` proves: an assignment becomes a notification and a schema-valid e-mail request; a
redelivery changes nothing; the owner can acknowledge and others cannot (401/403/404); the highest employee
snapshot wins and a tombstone stops e-mail; understaffing alerts every active manager.

## Done when

- [x] A swap request appears in `GET /api/v1/notifications` and in Mailpit (http://localhost:8025).
- [x] `mvn verify` is green, including the `*IT` on Testcontainers.
