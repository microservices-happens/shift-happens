# audit-service

**Owns:** an append-only log of every event.
**Database:** `audit-db` (Postgres). **Compose:** `audit-service`.

## Provides
- REST: [`contracts/openapi/audit.yaml`](../../contracts/openapi/audit.yaml)
  - `GET /auditlogs`, Administrator only, filterable by `eventType`, `aggregateId`, `correlationId` and time range

## Consumes
| Queue | Binding |
|---|---|
| `audit.events` | `#` (everything) |

Store the envelope unchanged, with a unique constraint on `eventId` so redelivery is harmless.

## Publishes
Nothing.

## Extract from monolith
`auditlog`. The monolith writes audit rows directly; here audit rows come only from events.

## Done when
- [ ] Following one `correlationId` shows the full chain, e.g. swap requested → swap approved → assignment updated.
