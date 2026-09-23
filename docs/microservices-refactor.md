# Microservice Refactor Guide

This document explains the target architecture and how the team moves toward it while the monolith keeps working. The focus is **Large Systems**: service boundaries, data ownership, messaging, CQRS and deployment. We deliberately keep integration technology to a minimum, so data moves in only two ways: REST/JSON and RabbitMQ events.

| Artifact | Purpose |
|---|---|
| `docker-compose.microservices.yml` | Runnable target system and implementation checklist |
| `contracts/` | **The contracts between services**: OpenAPI per service, event envelope, event catalog, queues |
| `services/<name>/README.md` | Scaffold per service: what it owns, provides, publishes and consumes, and when it is done |
| `docs/shift-happens-high-level-architecture.drawio` | Editable diagram |

The current `docker-compose.yml` remains the runnable monolith.

## Architecture at a glance

The browser talks only to the API gateway. It never reaches a business service or database directly.

| Service | Responsibility | Data |
|---|---|---|
| **Identity** | Login, accounts, JWT signing | Postgres |
| **Workforce** | Employees, contracts, departments, job roles, locations | Postgres |
| **Scheduling** | Shifts, assignments, approvals and **shift swaps** (CQRS) | Postgres write + Mongo read |
| **Leave** | Requests, types, approvals, immutable ledger (CQRS) | Postgres write + Mongo read |
| Notification | In-app notifications and email, fed by events | Postgres |
| Audit | Append-only log of every event | Postgres |

**Shift swaps are part of Scheduling.** A swap moves an existing assignment to another employee. Inside one service, approving the swap and reassigning the shift is a single local transaction. As a separate service it would need a distributed workflow with compensation for no real benefit.

## Communication

| From → To | How | Contract |
|---|---|---|
| Browser → gateway | HTTPS `/api/<resource>` (same URLs as the monolith) | – |
| Gateway → service | REST/JSON. The gateway strips `/api` and routes by path. For Scheduling and Leave, `GET` goes to the query role and writes go to the command role | `contracts/openapi/*.yaml` |
| Service → service (needs an answer now) | REST/JSON lookup, forwarding the caller's JWT | `contracts/openapi/workforce.yaml` |
| Service → service (something happened) | RabbitMQ topic exchange `shift-happens.events`, routing key = `eventType` | `contracts/events/` |

There is no gRPC, GraphQL or SSE. The gateway is plain Caddy with path routing and no custom code. Each service validates the JWT itself using identity's public key, because each service owns its own authorization rules.

The only synchronous service-to-service calls go to Workforce: Scheduling and Leave check that an employee exists, is active and holds the right job role before accepting a command. Everything else flows through events.

## Data ownership and CQRS

Every service owns its database, and no service reads another service's tables.

Scheduling and Leave each run as two containers built from one codebase (`APP_ROLE=command|query`):

- **Command:** Postgres write model. It writes events to an outbox table in the same transaction as the state change.
- **Query:** consumes events into a MongoDB read model shaped for the UI (overviews, balances). This model is eventually consistent and can be rebuilt by replaying events.

The Leave ledger is append-only: corrections are new entries, never updates.

## Events in one paragraph

Every event is an envelope (`eventId`, `eventType`, `aggregateId`, `aggregateVersion`, `correlationId` and so on) with a **full snapshot** of the entity in `data`. Each consumer has its own durable queue, so one event fans out to Scheduling-query, Notification and Audit without them competing. Consumers deduplicate by `eventId` and ignore older `aggregateVersion`s. The full list of events, schemas, queues and bindings is in [`contracts/events/catalog.md`](../contracts/events/catalog.md).

## Local usage

```bash
# Validate
docker compose -f docker-compose.microservices.yml --profile app config --quiet

# Infrastructure only (DBs, RabbitMQ, Mailpit, OTel collector)
docker compose -f docker-compose.microservices.yml up -d

# Whole system, once every services/* folder has a Dockerfile
docker compose -f docker-compose.microservices.yml --profile app up --build
```

- App: `https://localhost:8443` (Caddy local CA, so the browser may warn)
- RabbitMQ management: `http://localhost:15672`
- Mailpit: `http://localhost:8025`

Do not use the development passwords from Compose in a deployed environment.

## Migration order (strangler)

1. **Agree on `contracts/`.** Review the OpenAPI files and the event catalog together. After this, teams can work in parallel.
2. **Identity + Workforce.** Login keeps working, and employee events start flowing.
3. **Scheduling, including swaps.** Build the command side and outbox first, then the query projection.
4. **Leave.** Requests and approvals, then the ledger, then the query projection and the leave-conflict event into Scheduling.
5. **Notification + Audit.** Two independent subscribers demonstrate fan-out.
6. **Remove migrated monolith routes** once a cooperation test proves the replacement flow works.

Until a service exists, its Caddy route can temporarily point at the monolith (`reverse_proxy` to the old backend) so the frontend always works.

## Definition of done for a service

- Implements its `contracts/openapi/<service>.yaml` and the events listed in its `services/<name>/README.md`.
- Has its own Dockerfile, database, schema migrations and `/health`.
- Validates the JWT and enforces role rules for its own operations.
- Uses an outbox (producers) or idempotent handlers (consumers).
- Propagates `X-Correlation-Id` / `correlationId` and exports OpenTelemetry data.
- Has unit tests for business rules plus at least one cooperation test across a queue or REST call.
- Never accesses another service's database.

## Deployment mapping

Compose simulates the logical structure locally. In Kubernetes:

- Each container role becomes a Deployment and a Service, and `/health` becomes the readiness and liveness probes.
- Caddy is replaced by an Ingress with the same path rules.
- Stateless roles and queue consumers scale horizontally. Command and query roles scale independently.

The OpenTelemetry Collector currently prints to its debug exporter. Later it can forward to Grafana, Prometheus, Loki or Jaeger.
