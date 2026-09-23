# Service Contracts

This folder is the single source of truth for how the Shift Happens frontend and microservices talk to each other. A service may change anything internally, but a change here must be agreed by every provider and consumer of that contract.

| Contract | Standard | Files |
|---|---|---|
| REST APIs (frontend → services) | OpenAPI 3.1 | `openapi/<service>.yaml` |
| GraphQL API (frontend → Scheduling reads) | GraphQL SDL | `graphql/scheduling.graphql` |
| Events (service ↔ service) | AsyncAPI 3.1 + JSON Schema | `asyncapi.yaml`, `events/envelope.schema.json`, `events/payloads/*.schema.json` |
| Human-readable event overview and flows | – | `events/catalog.md` |

## Communication rules

| From → To | Style | Why |
|---|---|---|
| Browser → gateway | HTTPS | TLS terminates at the edge |
| Frontend → any service's commands, and Identity, Workforce, Leave, Notification and Audit reads | **REST** `/api/v1/...` | Simple resource CRUD, cacheable, easy to secure per route |
| Frontend → Scheduling reads | **GraphQL** `/api/graphql` | The schedule UI needs nested, composed data (shift → assignments → employees → AI suggestions) in one round trip |
| Service → service | **RabbitMQ events only** | Loose coupling: a service keeps working when another is down, and consumers scale independently |

**No synchronous calls between services.** If a service needs another service's data, it keeps a local copy built from events. For example, Scheduling keeps an employee replica so it can check qualifications without calling Workforce.

## Services

| Service | Owns | Browser API | Publishes | Consumes (queue) |
|---|---|---|---|---|
| identity-service | Accounts, password hashes, OIDC links, JWT keys | REST `openapi/identity.yaml` | – | `identity.accounts` |
| workforce-service | Employees, contracts, departments, job roles, locations | REST `openapi/workforce.yaml` | `workforce.employee.*` | – |
| scheduling-service | Shifts, assignments, approvals, **shift swaps** | REST writes `openapi/scheduling.yaml`, GraphQL reads | `scheduling.*` | `scheduling.command`, `scheduling.projection` |
| leave-service | Leave types, requests, approvals, immutable ledger; **saga orchestrator** | REST `openapi/leave.yaml` | `leave.*` | `leave.command`, `leave.projection` |
| ai-service | Replacement suggestions, LLM prompt/response log | – (health only) | `ai.replacement.suggested.v1` | `ai.suggestions` |
| notification-service | In-app notifications | REST `openapi/notification.yaml` | `notification.email.requested.v1` | `notification.events` |
| audit-service | Append-only event log | REST `openapi/audit.yaml` | – | `audit.events` |
| email-function *(serverless, not a microservice)* | – | – | – | `email.send` |

## REST conventions

- **Versioning.** Every service serves its resources under `/v1/`. The gateway exposes them as `/api/v1/<resource>` and strips `/api`. A breaking change gets `/v2/...` next to `/v1/...` until the frontend has moved.
- **Resources.** The monolith's collection names (`/v1/shifts`, `/v1/leaverequests` and so on) with `GET`, `POST`, `PUT` or `PATCH`, and `DELETE`.
- **CQRS.** For Leave, the gateway sends `GET` to the query role and other methods to the command role. For Scheduling, REST only covers writes, and all reads use GraphQL. Command endpoints return `202 Accepted` with the resulting resource, and read models catch up from events.
- **Idempotency.** Every `POST` requires an `Idempotency-Key` header (UUID), which the frontend generates once per user action.
  - A repeated request with the same key returns the stored first response and has no second side effect.
  - The same key with a different body returns `409`.
  - Keys are kept for 24 h. `PUT`, `PATCH` and `DELETE` are idempotent by definition.
- **Deletes are soft.** The row gets `deleted_at`, and a `*.deleted.v1` tombstone event is published.
- **Payloads.** JSON with camelCase fields. Timestamps are ISO-8601 strings. Dates are `YYYY-MM-DD`. IDs are integers, so existing monolith data migrates unchanged.
- **Errors.** Use `application/problem+json` (RFC 9457; the `Problem` schema in every OpenAPI file). Status codes: `400` validation, `401` no or invalid token, `403` wrong role, `404` not found, `409` business-rule or idempotency conflict.
- **Health.** `GET /health` returns `200 {"status":"UP"}` without authentication. The gateway, Compose and Kubernetes probes use it.

## Authentication and authorization

1. **Local login:** `POST /api/v1/auth/login` with email and password returns a JWT.
2. **Third-party login:** `GET /api/v1/auth/oidc/{google|github}/authorize` runs the OAuth 2.0 authorization-code flow with PKCE. The callback matches the provider's verified email to an existing active account and redirects to the frontend with a Shift Happens JWT. Unknown emails are refused.
3. The browser sends `Authorization: Bearer <jwt>` on REST and GraphQL requests.
4. **Every service validates the JWT itself.** It checks the signature against identity's public key at `JWT_JWKS_URL` (cached), plus `iss` and `exp`. The gateway has no business logic and does not check tokens.
5. **Role rules** (from the monolith):
   - `GET` requests need any valid token.
   - Writes need `Administrator` or `Manager`.
   - Employees may also `POST /v1/leaverequests` and `POST /v1/shiftswaps` for themselves.
   - `/v1/auditlogs` is Administrator only.

JWT claims (RS256):

| Claim | Example | Meaning |
|---|---|---|
| `sub` | `"anna@shift.dk"` | Login email |
| `employeeId` | `42` | Workforce employee id |
| `role` | `"Manager"` | `Administrator`, `Manager` or `Employee` |
| `iss` | `"shift-happens-identity"` | Issuer |
| `iat`, `exp` | epoch seconds | Issued at / expiry |

## Events

The formal contract is `asyncapi.yaml`, and `events/catalog.md` lists every event, queue and flow in tables. The rules:

- **Exchange and routing.** One topic exchange, `shift-happens.events`. The routing key equals `eventType` (`<context>.<entity>.<what-happened>.v<N>`).
- **Envelope.** Every message body is the envelope (`events/envelope.schema.json`) with the payload in `data`.
- **Snapshot pattern.** `data` is a full snapshot of the entity after the change, not a diff, so consumers can upsert it without calling back.
- **Tombstones.** A delete publishes `*.deleted.v1` (`payloads/tombstone.schema.json`). Consumers keep the tombstone so a late update cannot resurrect the entity.
- **Queues.** Each consumer owns one durable queue, bound to the exact routing keys it handles. Replicas of the same consumer share the queue as competing consumers.
- **Retries.** After 3 failed attempts a message goes to `shift-happens.dlx`.
- **Outbox.** Producers write events to an outbox table in the same transaction as the state change, and a relay publishes them.
- **Idempotent consumers.** Consumers store processed `eventId`s in the same transaction as their own change.
- **Commutative consumers.** Arrival order must not change the result:
  - Upserts keep the snapshot with the highest `aggregateVersion`, and older ones are ignored.
  - Tombstones win over lower versions.
  - Balances are sums of ledger entries, and addition is order-independent.

## Tracing

- **HTTP:** the `X-Correlation-Id` header. The first service generates one if it is missing.
- **Events:** `correlationId` is copied from the triggering request or event, and `causationId` holds the triggering `eventId`.
- **Telemetry:** everything is exported as OpenTelemetry traces, logs and metrics.

## Versioning

| What | How |
|---|---|
| REST | URL version `/v1`. Additive changes stay in v1, and breaking changes get `/v2` |
| GraphQL | Not versioned: add fields and mark old ones `@deprecated` |
| Events | `.vN` suffix in `eventType`/routing key. For a breaking change, publish `.v2` alongside `.v1` until every consumer has moved |
| Databases | Flyway migrations per service (`V1__init.sql`, …); Mongo read models are rebuilt from events |
| This folder | Changed only by pull request, reviewed by the owners of every affected service |
