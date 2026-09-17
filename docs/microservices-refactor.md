# Microservice Refactor Guide

This document explains the proposed target architecture and how the team can move toward it without stopping work on the current application.

The current `docker-compose.yml` remains the runnable monolith. `docker-compose.microservices.yml` describes the target system and acts as an implementation checklist. Application folders under `services/` are intentionally absent until the corresponding bounded context is extracted.

The editable architecture diagram is `docs/shift-happens-high-level-architecture.drawio`.

## Architecture at a glance

The browser communicates only with the edge and the GraphQL BFF/API Gateway. It never connects directly to a business service or database.

The five core business domains are:

- **Identity:** local credentials, external OIDC login, JWT issuance, and account lifecycle.
- **Workforce:** employees, contracts, departments, job roles, and work locations.
- **Scheduling:** shifts, required roles, assignments, conflict checking, and schedule queries.
- **Leave:** leave requests, leave types, approval decisions, and the immutable leave ledger.
- **Shift Swap:** swap requests, eligibility workflow, acceptance, rejection, and expiry.

Notification, Audit, and AI are supporting services rather than additional core business domains.

## Communication choices

- **Browser to edge:** HTTPS. Caddy represents TLS termination and a future Kubernetes Ingress or cloud load balancer.
- **Browser to BFF:** REST for commands and authentication, GraphQL for composed reads, and SSE for live in-app notifications.
- **BFF to business services:** gRPC with Protocol Buffers for synchronous internal requests.
- **Service to service:** RabbitMQ topic exchanges for asynchronous domain events and publish/subscribe fan-out.
- **Email:** Notification Service consumes events and sends messages through SMTP. Mailpit is the local email sink.

The BFF validates the token and performs coarse route-level authorization. Each business service must still enforce authorization for its own operations because it owns the business rules and data.

## Data ownership

Every service owns its data. A service must not read or write another service's database directly.

Scheduling and Leave use CQRS because their read and write needs differ:

- The command process owns the relational write model and transactional outbox.
- The query process consumes events and owns a MongoDB read model.
- Both processes are deployments of the same bounded-context codebase, not separate business microservices.
- The read model is eventually consistent and can be rebuilt from published events where supported.

The Leave ledger is append-only and idempotent. It should only be described as full event sourcing if the implemented service can rebuild its authoritative state from the stored events. The Audit Service is an append-only event consumer, not the source of truth for other services.

## Event-processing rules

Applications declare RabbitMQ exchanges, bindings, durable queues, retries, and dead-letter routing at startup. Compose only supplies the broker and configuration values.

Every event envelope should contain at least:

- `eventId`
- `eventType` and schema version
- `aggregateId` and `aggregateVersion`
- `occurredAt`
- `correlationId` and `causationId`
- tenant/company identifier when multi-tenancy is introduced

Consumers store processed `eventId` values to make redelivery idempotent. For state-update projections, an older `aggregateVersion` or timestamp is ignored. This makes last-write-wins handlers commutative for the specific fields where that rule is valid; timestamps alone do not make every business operation commutative.

Each subscriber has its own queue. For example, Scheduling Projection, Notification, and Audit may all receive the same `shift.updated` event without competing for one message. Multiple replicas of the same subscriber share its queue and act as competing consumers.

Command services should use the transactional outbox pattern so a database commit and its event cannot silently diverge.

## Local usage

Validate the target Compose model:

```bash
docker compose -f docker-compose.microservices.yml config --quiet
docker compose -f docker-compose.microservices.yml --profile app --profile ai config --quiet
```

Start infrastructure before application services exist:

```bash
docker compose -f docker-compose.microservices.yml up -d
```

This starts the service-owned databases, RabbitMQ, Mailpit, and the OpenTelemetry Collector. Useful local endpoints are:

- RabbitMQ management: `http://localhost:15672`
- Mailpit: `http://localhost:8025`

After all required `services/*` build contexts exist, start the target application:

```bash
docker compose -f docker-compose.microservices.yml --profile app up --build
```

The target application is exposed at `https://localhost:8443`. Caddy uses a local certificate authority, so a browser may warn until its local CA is trusted.

Enable the optional AI Assistant with both profiles:

```bash
docker compose -f docker-compose.microservices.yml \
  --profile app --profile ai up --build
```

Do not use the development passwords from Compose in a deployed environment. Kubernetes or a cloud secret manager should provide production credentials.

## Recommended migration order

Use a strangler-style migration: keep the monolith working while one responsibility at a time moves behind the gateway.

1. **Agree on contracts.** Define JWT claims, the event envelope, RabbitMQ routing-key naming, protobuf package conventions, `/health`, and correlation IDs.
2. **Create the edge and BFF skeleton.** Initially, it may proxy unchanged endpoints to the monolith.
3. **Extract Identity and Workforce.** They establish authentication and the reference data used by later services.
4. **Extract Scheduling.** Begin with its relational command side, add the outbox, then build the asynchronous query projection.
5. **Extract Leave.** Implement requests and approval first, followed by the immutable ledger and query projection.
6. **Extract Shift Swap.** Implement it as an event-driven workflow. Call it a saga only when compensating actions are implemented.
7. **Add Notification and Audit subscribers.** Their separate queues demonstrate publish/subscribe fan-out.
8. **Add the AI Assistant last.** The BFF supplies explicitly approved, read-only context; the assistant has no database or RabbitMQ access.
9. **Remove migrated monolith routes.** Delete old code only after cooperation tests prove that the replacement workflow works.

This order is a recommendation, not a requirement. A team can work in parallel after the shared contracts and ownership boundaries are agreed.

## Definition of done for a service

A service extraction is complete when it has:

- A clear owner and bounded responsibility.
- Its own build, container image, schema migrations, and database.
- Versioned gRPC, REST, GraphQL, or event contracts as applicable.
- Independent authentication and operation-level authorization.
- Idempotent event handlers and an outbox for reliable publication where needed.
- Structured logs, correlation IDs, OpenTelemetry instrumentation, and `/health`.
- Unit tests for business rules, database/broker integration tests, and at least one cooperation test.
- CI steps that build, test, scan, and publish the image.
- No direct access to another service's database.

## Deployment mapping

Docker Compose simulates the logical structure locally. In Kubernetes, each application role becomes a Deployment and Service, each health endpoint becomes readiness/liveness probes, and Caddy is replaced by an Ingress controller or managed gateway. Production databases and RabbitMQ can be managed services. Horizontal scaling applies to stateless application processes and RabbitMQ consumers; database and broker scaling require their own plans.

The initial OpenTelemetry Collector accepts logs, metrics, and traces and writes them with a debug exporter. A later deployment can route them to Grafana, Prometheus, Loki, Jaeger, or a managed observability platform.
