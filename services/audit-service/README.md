# audit-service

**Owns:** an append-only log of every business event.
**Database:** `audit-db` (Postgres, Flyway). **Compose:** `audit-service`.

> **Status: walking skeleton (P4.5 not started).** The application only serves `GET /health` and the
> Swagger UI below. It has no database access, no Flyway migration (`db/migration` holds only a
> `.gitkeep`), no RabbitMQ listener and no JWT validation (P1.7); `application.yml` reads only
> `HTTP_PORT`. The `DB_*`, `RABBITMQ_*` and `JWT_*` variables that `docker-compose.microservices.yml`
> sets are not used yet. Provides and Consumes below are the design from the contracts, **not
> implemented yet**.

## Provides
REST: [`contracts/openapi/audit.yaml`](../../contracts/openapi/audit.yaml). `GET /v1/auditlogs`, Administrator only, filterable by `eventType`, `aggregateId`, `correlationId` and time range. Not implemented yet (P4.5). Implemented: `GET /health` (public).

## Consumes
Queue `audit.events`: the routing keys listed for it in [`catalog.md`](../../contracts/events/catalog.md) (the `workforce.employee.*`, `scheduling.*`, `leave.request.*`, `leave.ledger.*` and `ai.*` events; the catalog does not list `leave.type.*`, and email payloads are excluded because they contain personal data). It stores the envelope unchanged, with a unique constraint on `eventId`. Not implemented yet (P4.5): the queue is not declared by this service.

## Run and test
`./mvnw -B -f services/audit-service/pom.xml test` runs `AuditServiceApplicationTests` (`/health` is
public and `UP`) and `SwaggerContractTest` (the contract is on the classpath). There are no `*IT` tests yet.

## Extract from monolith
`auditlog`. In the monolith, services write audit rows directly. Here, audit rows come only from events.

## API docs (Swagger UI)

Swagger UI is at `http://localhost:8080/swagger-ui.html` (service port; through the gateway it is not routed). It renders
the hand-written contract `contracts/openapi/audit.yaml`, not a spec generated from the controllers. Maven copies
the file onto the classpath at build time (`/openapi/audit.yaml`); never edit the copy. The Docker build gets the file
through `--build-context contracts=contracts` (set in Compose and CI).

## Done when
- [ ] Filtering by one `correlationId` shows a whole saga, e.g. approval-started → leave-release.completed → approved. (Not implemented: P4.5.)
