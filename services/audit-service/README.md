# audit-service

**Owns:** an append-only log of every business event.
**Database:** `audit-db` (Postgres, Flyway). **Compose:** `audit-service`.

## Provides
REST: [`contracts/openapi/audit.yaml`](../../contracts/openapi/audit.yaml). `GET /v1/auditlogs`, Administrator only, filterable by `eventType`, `aggregateId`, `correlationId` and time range.

## Consumes
Queue `audit.events`: every `workforce.*`, `scheduling.*`, `leave.*` and `ai.*` event (email payloads are excluded because they contain personal data). It stores the envelope unchanged, with a unique constraint on `eventId`.

## Extract from monolith
`auditlog`. In the monolith, services write audit rows directly. Here, audit rows come only from events.

## Done when
- [ ] Filtering by one `correlationId` shows a whole saga, e.g. approval-started → leave-release.completed → approved.
