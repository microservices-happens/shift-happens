# 0003. GraphQL on the Scheduling query role; gateway without logic

- **Status:** Accepted
- **Date:** 2026-09-23

## Context
The course requires the frontend to use both REST and GraphQL. A GraphQL BFF in the gateway would contain logic and become a bottleneck, and a gateway only counts as a microservice if it contains business logic.

## Decision
- `scheduling-query` serves GraphQL at `/graphql` (reads only) from its Mongo read model.
- All writes are REST on the owning services.
- The gateway (Caddy / Ingress) only terminates TLS and routes by path.

## Consequences
- **Easier:** the schedule UI gets its nested data (shift → assignments → employees → AI suggestions) in one request, and no extra service is needed.
- **Harder:** the frontend uses two clients (fetch and Apollo), and GraphQL authorization lives in scheduling-query.

## Alternatives considered
- GraphQL BFF composing all services: more flexible, but it needs synchronous fan-out to every service.
- GraphQL everywhere: unnecessary for simple CRUD.
