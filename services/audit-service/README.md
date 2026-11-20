# audit-service

**Owns:** an append-only log of every business event.
**Database:** `audit-db` (Postgres, Flyway). **Compose:** `audit-service`.
**Contract:** [`contracts/openapi/audit.yaml`](../../contracts/openapi/audit.yaml).

> **Status: implemented (P4.5).** Consumes the routing keys that `catalog.md` lists for `audit.events`,
> appends each envelope unchanged (idempotent by `eventId`) and serves the Administrator-only search with
> JWT validation. `AuditServiceIT` covers the consumer, duplicate delivery, filters and the 401/403 matrix.

## Provides

| Endpoint | Who | Behaviour |
|---|---|---|
| `GET /v1/auditlogs?eventType=&aggregateId=&correlationId=&from=&to=` | **Administrator only** | Newest first, bounded by `AUDIT_MAX_ROWS` (default 1000); every filter is optional |
| `GET /health` | public | Compose/Kubernetes probe |

There is deliberately **no** update or delete endpoint: an audit trail that can be rewritten is not an audit
trail. `PATCH`/`PUT`/`DELETE` are refused by the role rule (and unknown methods by Spring). Errors are
`application/problem+json`. Swagger UI at `/swagger-ui.html` renders the contract.

## Consumes (queue `audit.events`)

Every routing key in [`contracts/events/catalog.md`](../../contracts/events/catalog.md): the three
`workforce.employee.*`, the ten `scheduling.*` (shift, assignment, swap, leave-release), the six
`leave.request.*`/`leave.ledger.entry-recorded.*` and `ai.replacement.suggested.v1`. E-mail requests are not
audited: they carry a recipient address and no business change.

Each row stores `event_id`, `event_type`, `occurred_at`, `producer`, `aggregate_id`, `aggregate_version`,
`correlation_id`, `received_at` and the original envelope as `jsonb`. The unique `event_id` makes the
consumer idempotent: a redelivery is ignored without a separate `processed_event` table.

## Why this is a separate service

The audit log is a cross-cutting read with its own retention, security (Administrator only) and volume. It
must not slow down a business write and it must survive a producer's database loss, so it is fed only by
events and owns its own store. It is also the query that makes a saga visible: filtering by one
`correlationId` returns every step.

## Run and test

```bash
mvn -B -f services/audit-service/pom.xml test      # SwaggerContractTest
mvn -B -f services/audit-service/pom.xml verify    # + AuditServiceIT (Postgres + RabbitMQ)
```

`AuditServiceIT` proves: two events are appended once each, a redelivery is ignored, the stored envelope
matches `contracts/events/envelope.schema.json`, each filter narrows the result, and only an Administrator
gets `200` (Manager/Employee `403`, anonymous `401`).

## Done when

- [x] Filtering by one `correlationId` shows a whole saga (approval-started → leave-release.completed →
      approved); the IT asserts the correlation filter over two events.
- [x] `mvn verify` is green, including the `*IT` on Testcontainers.
