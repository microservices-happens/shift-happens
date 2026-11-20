# notification-service

**Owns:** in-app notifications.
**Database:** `notification-db` (Postgres, Flyway). **Compose:** `notification-service`.

> **Status: walking skeleton (P4.3 not started).** The application only serves `GET /health` and the
> Swagger UI below. It has no database access, no Flyway migration (`db/migration` holds only a
> `.gitkeep`), no RabbitMQ listener, no outbox and no JWT validation (P1.7); `application.yml` reads only
> `HTTP_PORT`. The `DB_*`, `RABBITMQ_*`, `OUTBOX_ENABLED` and `JWT_*` variables that
> `docker-compose.microservices.yml` sets are not used yet. Everything under Provides, Consumes and
> Publishes is the design from the contracts, **not implemented yet**.

## Provides
REST: [`contracts/openapi/notification.yaml`](../../contracts/openapi/notification.yaml). `GET /v1/notifications` (the frontend polls it) and `PATCH /v1/notifications/{id}`. Not implemented yet (P4.3; the frontend side is P5.5). Implemented: `GET /health` (public).

## Consumes
Queue `notification.events`: see the routing keys in [`catalog.md`](../../contracts/events/catalog.md). It stores one notification per affected employee, deduplicated by `eventId`. Not implemented yet (P4.3): the queue is not declared by this service.

## Publishes (outbox)
`notification.email.requested.v1` → sent by [`functions/email-function`](../../functions/email-function). Not implemented yet (P4.3).

## Run and test
`./mvnw -B -f services/notification-service/pom.xml test` runs `NotificationServiceApplicationTests`
(`/health` is public and `UP`) and `SwaggerContractTest` (the contract is on the classpath). There are no
`*IT` tests yet.

## API docs (Swagger UI)

Swagger UI is at `http://localhost:8080/swagger-ui.html` (service port; through the gateway it is not routed). It renders
the hand-written contract `contracts/openapi/notification.yaml`, not a spec generated from the controllers. Maven copies
the file onto the classpath at build time (`/openapi/notification.yaml`); never edit the copy. The Docker build gets the file
through `--build-context contracts=contracts` (set in Compose and CI).

## Done when
- [ ] A swap request appears in `GET /api/v1/notifications` and in Mailpit (http://localhost:8025). (Not implemented: P4.3, P4.4.)
