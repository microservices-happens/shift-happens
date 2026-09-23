# 0001. Services communicate only through RabbitMQ events

- **Status:** Accepted
- **Date:** 2026-09-23

## Context
The course requires backend services to communicate primarily asynchronously. Synchronous service-to-service calls create chains of failures and timeouts. For example, Scheduling can't assign anyone while Workforce is down.

## Decision
- Services do not call each other synchronously. They publish domain events to the topic exchange `shift-happens.events`, with routing key = `eventType`.
- A service that needs another service's data keeps a local copy built from snapshot events.
- The browser reaches services through REST and GraphQL via the gateway.

## Consequences
- **Easier:** a service keeps working when another is down, and consumers scale independently.
- **Harder:** local copies are eventually consistent (usually under a second behind). Every consumer must be idempotent and order-independent, and producers must use an outbox.
- All events are documented in `contracts/asyncapi.yaml`.

## Alternatives considered
- REST lookups (e.g. Scheduling → Workforce): simpler, but they couple availability and contradict the course requirement.
- gRPC: faster, but still synchronous.
