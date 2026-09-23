# notification-service

**Owns:** in-app notifications and the email outbox.
**Database:** `notification-db` (Postgres). **Compose:** `notification-service`.

## Provides
- REST: [`contracts/openapi/notification.yaml`](../../contracts/openapi/notification.yaml)
  - `GET /notifications` (the frontend polls it)
  - `PATCH /notifications/{id}`

## Consumes
| Queue | Binding |
|---|---|
| `notification.events` | `scheduling.assignment.created.*`, `scheduling.shift.cancelled.*`, `scheduling.swap.#`, `leave.request.#` |

For each event, store a notification for the affected employee(s) and send an email over SMTP (Mailpit locally). Deduplicate by `eventId`.

## Publishes
Nothing.

## Done when
- [ ] A swap request shows up in Mailpit (http://localhost:8025) and in `GET /api/notifications`.
