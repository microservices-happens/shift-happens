# Java and Spring conventions

Stack: Java 21, Spring Boot 3.5.10, Maven, **one standalone `pom.xml` per service**.
Package root: `dk.ek.shift_happens.<service>` (the folder name without `-service` suffix:
`workforce`, `scheduling`, `leave`, `ai`, `notification`, `audit`, `identity`).

## Layering

`api/` → `application/` → `domain/`, with `infrastructure/` implementing the ports.

- `domain/`: plain Java. No Spring, no JPA/Mongo annotations, no framework types.
- `application/`: use cases, transaction boundaries (`@Transactional` belongs here only).
- `api/`: controllers and GraphQL resolvers, request/response DTOs, validation, mapping.
  No business rules.
- `infrastructure/persistence/`: entities/repositories; Flyway scripts in
  `src/main/resources/db/migration` (`V1__init.sql`, forward-only).
- `infrastructure/messaging/`: listeners, outbox relay, envelope mapping.
- `infrastructure/llm/`: the external model client (ai-service only).
- `config/`: security, AMQP, OpenTelemetry, OpenAPI.

## Code conventions

- Constructor injection with `private final` fields. No field injection.
- Records for DTOs, events and commands. Do not add Lombok to a service that does not use it.
- REST paths are `/v1/<resource>` inside the service; the gateway adds `/api`. Command endpoints
  that are projected asynchronously return `202 Accepted` with the resource id.
- Errors: RFC 9457 `application/problem+json` through a `@RestControllerAdvice`.
- Configuration through environment variables only. Names are fixed by
  `docker-compose.microservices.yml` and `contracts/README.md`: `HTTP_PORT`, `APP_ROLE`,
  `RABBITMQ_*`, `EVENT_EXCHANGE`, `DEAD_LETTER_EXCHANGE`, `JWT_ISSUER`, `JWT_JWKS_URL`,
  `OTEL_EXPORTER_OTLP_ENDPOINT`. Never hard-code hosts, ports or secrets.
- Every service exposes `GET /health` through actuator (base path `/`). Compose and the
  Kubernetes probes depend on it; do not rename or secure it.
- Every service validates the JWT itself (`spring-boot-starter-oauth2-resource-server`, JWKS
  cached). Roles: `Administrator`, `Manager`, `Employee`. The gateway never authorizes.
- Logging: SLF4J with the correlation id in MDC. Never log tokens, passwords or salary.

## Messaging conventions

- Publish through the transactional outbox in the same transaction as the state change; a relay
  publishes afterwards. A state change must never be committed without its event.
- Envelope fields: `eventId`, `eventType`, `aggregateId`, `aggregateVersion`, `occurredAt`,
  `correlationId`, `causationId`, `data`.
- Consumers own one durable queue, store processed `eventId`s in the same transaction as their
  change, keep the highest `aggregateVersion`, and let a tombstone win over lower versions.
- Three failed attempts, then the DLX. Never requeue forever.
