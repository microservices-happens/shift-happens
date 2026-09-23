# Service Contracts

This folder is the single source of truth for how Shift Happens microservices talk to each other. A service may change anything internally, but a change here must be agreed by every provider and consumer listed for that contract.

There are exactly two ways to move data between services:

| Style | When to use | Contract |
|---|---|---|
| **REST/JSON over HTTP** | The caller needs an answer now (browser requests, validation lookups). | `openapi/*.yaml` |
| **RabbitMQ event** | Something happened and other services may react later. | `events/` |

No gRPC, GraphQL, shared databases or shared libraries. If a service needs another service's data often, it keeps a local copy built from events instead of calling it on every request.

## Services at a glance

| Service | Owns | REST contract | Publishes | Consumes |
|---|---|---|---|---|
| identity-service | Accounts, password hashes, JWT signing keys | `openapi/identity.yaml` | – | `workforce.employee.*` |
| workforce-service | Employees, contracts, departments, job roles, employee job roles, work locations | `openapi/workforce.yaml` | `workforce.employee.*` | – |
| scheduling-service | Shifts, required job roles, assignments, shift approvals, **shift swaps** and swap approvals | `openapi/scheduling.yaml` | `scheduling.*` | `leave.request.approved`, `workforce.employee.*`, own events |
| leave-service | Leave types, requests, approvals, immutable leave ledger | `openapi/leave.yaml` | `leave.*` | `workforce.employee.*`, own events |
| notification-service | In-app notifications, email delivery | `openapi/notification.yaml` | – | see `events/catalog.md` |
| audit-service | Append-only log of every event | `openapi/audit.yaml` | – | `#` (everything) |

Shift swaps live inside Scheduling because a swap only moves an existing shift assignment from one employee to another. Keeping them in the same service lets the approval and the reassignment happen in one database transaction, so no distributed workflow is needed.

## REST conventions

- **Base path.** Every service serves its resources from `/`. The API gateway exposes them as `/api/<resource>` and strips the `/api` prefix. The browser keeps using the same URLs as the monolith.
- **Resources and verbs.** Use the monolith's collection names (`/shifts`, `/leaverequests` and so on) with `GET` list, `GET /{id}`, `POST`, `PUT` or `PATCH /{id}` and `DELETE /{id}`.
- **Scheduling and Leave CQRS.** The gateway sends `GET` to the `query` role and every other method to the `command` role. Both roles serve the same OpenAPI file. A write returns `202 Accepted` with the new resource, and the read model catches up from events, usually within milliseconds.
- **Payloads.** JSON with camelCase fields. Timestamps are ISO-8601 strings. Dates are `YYYY-MM-DD`. IDs are integers, so existing monolith data migrates unchanged.
- **Errors.** Use `application/problem+json` (RFC 9457) with `type`, `title`, `status` and `detail` (the `Problem` schema in every OpenAPI file). Status codes: `400` validation, `401` no or invalid token, `403` wrong role, `404` not found, `409` business-rule conflict.
- **Health.** `GET /health` returns `200 {"status":"UP"}` without authentication. The gateway and Compose use it.

## Authentication

1. The browser calls `POST /api/auth/login` and gets a JWT from identity-service.
2. The browser sends `Authorization: Bearer <jwt>` on every request.
3. **Every service validates the JWT itself.** It checks the signature against identity's public key at `JWT_JWKS_URL` and caches it. It checks `iss = shift-happens-identity` and `exp`. The gateway does not check tokens.
4. Service-to-service REST calls forward the caller's `Authorization` header unchanged.

JWT claims (RS256):

| Claim | Example | Meaning |
|---|---|---|
| `sub` | `"anna@shift.dk"` | Login email |
| `employeeId` | `42` | Workforce employee id |
| `role` | `"Manager"` | `Administrator`, `Manager` or `Employee` |
| `iss` | `"shift-happens-identity"` | Issuer |
| `iat`, `exp` | epoch seconds | Issued at / expiry |

## Tracing

Every request and event carries a correlation id so one user action can be followed across services:

- **HTTP:** `X-Correlation-Id` header. The gateway or first service generates a UUID if it is missing, and every outgoing call copies it.
- **Events:** the `correlationId` field in the envelope, copied from the request or event that caused it.

## Events

See [`events/catalog.md`](events/catalog.md) for every event, its payload schema, producer and consumers. The key rules:

- One topic exchange: `shift-happens.events`. The routing key equals `eventType`, for example `scheduling.swap.approved.v1`.
- Every message body is the envelope (`events/envelope.schema.json`) with the payload in `data`.
- **The payload is a full snapshot** of the entity after the change, not a diff. Consumers can upsert it directly and never need to call back to the producer.
- Each consuming service declares **its own durable queue** and bindings at startup. Several replicas of the same service share that queue as competing consumers.
- Failed messages go to `shift-happens.dlx` after 3 attempts.
- **Producers** write the event to an outbox table in the same transaction as the state change, and a relay publishes it. Publishing directly after commit is acceptable for the first iteration, but mark it as a TODO.
- **Consumers** must be idempotent. They store processed `eventId`s and ignore snapshots whose `aggregateVersion` is lower than the one they already hold.

## Versioning

- Adding an optional field is non-breaking and needs no version bump.
- Removing or renaming a field, or changing its meaning, is breaking. Publish a new event version (`.v2`) alongside the old one until every consumer has moved, or add a new REST path.
