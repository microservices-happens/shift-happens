# notification-service

**Owns:** in-app notifications.
**Database:** `notification-db` (Postgres, Flyway). **Compose:** `notification-service`.

## Provides
REST: [`contracts/openapi/notification.yaml`](../../contracts/openapi/notification.yaml). `GET /v1/notifications` (the frontend polls it) and `PATCH /v1/notifications/{id}`.

## Consumes
Queue `notification.events`: see the routing keys in [`catalog.md`](../../contracts/events/catalog.md). It stores one notification per affected employee, deduplicated by `eventId`.

## Publishes (outbox)
`notification.email.requested.v1` → sent by [`functions/email-function`](../../functions/email-function).

## API docs (Swagger UI)

Swagger UI is at `http://localhost:8080/swagger-ui.html` (service port; through the gateway it is not routed). It renders
the hand-written contract `contracts/openapi/notification.yaml`, not a spec generated from the controllers. Maven copies
the file onto the classpath at build time (`/openapi/notification.yaml`); never edit the copy. The Docker build gets the file
through `--build-context contracts=contracts` (set in Compose and CI).

## Done when
- [ ] A swap request appears in `GET /api/v1/notifications` and in Mailpit (http://localhost:8025).
