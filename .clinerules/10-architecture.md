# Architecture facts

Seven backend microservices, two of them deployed in a CQRS command/query pair, plus one
serverless email function. Full description: `docs/architecture.md`; contracts: `contracts/`.

| Service | Path | Owns | Browser API | Publishes | Consumes (queue) |
|---|---|---|---|---|---|
| identity-service | `services/identity-service` | Accounts, password hashes, OIDC links, JWT keys | REST `openapi/identity.yaml` | – | `identity.accounts` |
| workforce-service | `services/workforce-service` | Employees, contracts, departments, job roles, locations | REST `openapi/workforce.yaml` | `workforce.employee.*` | – |
| scheduling-service | `services/scheduling-service` | Shifts, assignments, approvals, swaps | REST writes `openapi/scheduling.yaml`, GraphQL reads `graphql/scheduling.graphql` | `scheduling.*` | `scheduling.command`, `scheduling.projection` |
| leave-service | `services/leave-service` | Leave types, requests, approvals, immutable ledger, saga orchestrator | REST `openapi/leave.yaml` | `leave.*` | `leave.command`, `leave.projection` |
| ai-service | `services/ai-service` | Replacement suggestions, prompt/response log | health only | `ai.replacement.suggested.v1` | `ai.suggestions` |
| notification-service | `services/notification-service` | In-app notifications | REST `openapi/notification.yaml` | `notification.email.requested.v1` | `notification.events` |
| audit-service | `services/audit-service` | Append-only event log | REST `openapi/audit.yaml` | – | `audit.events` |
| email-function | `functions/email-function` | Serverless SMTP sender (not a microservice) | – | – | `email.send` |

## Communication

- Browser → gateway (Caddy locally, NGINX Ingress in Kubernetes): HTTPS only.
- Frontend → services: REST `/api/v1/...` for commands and most reads; GraphQL `/api/graphql`
  for scheduling reads. The gateway routes by path and contains no business logic.
- Service → service: RabbitMQ events only, through the `shift-happens.events` topic exchange and
  a dead-letter exchange `shift-happens.dlx`. Routing key == `eventType` ==
  `<context>.<entity>.<event>.vN`.
- Every event body is `contracts/events/envelope.schema.json`; `data` is a full entity snapshot.

## CQRS roles

`scheduling-service` and `leave-service` are one codebase deployed twice, selected by
`APP_ROLE=command|query`:

- command role: PostgreSQL + transactional outbox + local replicas, REST writes;
- query role: MongoDB read model built from events, GraphQL (scheduling) or REST GETs (leave).

Read models must be rebuildable from events, and reads must keep working when the command role is
down.

## Patterns that must stay demonstrable

CQRS (scheduling, leave), transactional outbox, idempotent REST commands (`Idempotency-Key`) and
idempotent consumers (`processed_events`), tombstone deletes, snapshot events, commutative
handlers (highest `aggregateVersion` wins), immutable leave ledger, orchestrated leave-approval
saga with compensation, serverless email job, caching of the JWKS.

Do not introduce synchronous service-to-service calls, shared databases, or a shared domain
library; all three break the architecture the report describes.
