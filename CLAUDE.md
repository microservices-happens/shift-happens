# CLAUDE.md — Shift Happens

Microservices rewrite of the Shift Happens monolith (DLS final project). Rules below were checked
against the code on 2026-11-04. Where a rule describes the target rather than today's code, it says so.

## Before you change anything

- Read the contract and README for the area you touch: `contracts/README.md`,
  `contracts/openapi/<service>.yaml`, `contracts/asyncapi.yaml`, `services/<service>/README.md`.
  Read `docs/architecture.md` §4 before any design decision.
- `contracts/` is the source of truth. Do not edit it unless asked; if code needs a contract change,
  say so first.
- Narrowest change that works. One service per PR unless a contract changes.
- Ask when a request is ambiguous or when docs and code disagree. Do not silently pick one.

## Architecture boundaries (do not break)

- Seven services in `services/` (identity, workforce, scheduling, leave, ai, notification, audit)
  plus `functions/email-function`. Each has its own `pom.xml`, database and image.
- Service → service is RabbitMQ only (`shift-happens.events` topic exchange, `shift-happens.dlx`).
  No synchronous calls (no RestTemplate/WebClient/Feign between services), no shared database,
  no shared library or shared Maven parent. Patterns are copied per service on purpose.
- Routing key = `eventType` = `<context>.<entity>.<event>.vN`. Envelope:
  `contracts/events/envelope.schema.json`; `data` is a full entity snapshot.
- Scheduling and leave are CQRS: one codebase, `APP_ROLE=command|query` (Postgres + outbox for
  commands, Mongo read model for queries). The query sides are not built yet.
- The monolith (`src/`, root `pom.xml`, `docker-compose_old.yml`, `*_old*`, `scripts_old/`,
  `docs/old/`) is reference only. Never refactor it.

## Java conventions (as used in scheduling and workforce)

- Java 21, Spring Boot 3.5.10, Maven. Package root `dk.ek.shift_happens.<service>`.
- Layers: `api/` → `application/` → `domain/`, `infrastructure/` implements ports, `config/` for wiring.
  `domain/` is plain Java (no Spring/JPA imports). `@Transactional` belongs in `application/`.
- Constructor injection with `private final` fields; records for DTOs, events and commands; no Lombok.
- REST paths `/v1/<resource>` (the gateway adds `/api`). Errors as RFC 9457 problem+json via a
  `@RestControllerAdvice` (`ApiExceptionHandler`). Async-projected commands return `202 Accepted`.
- POST endpoints take an `Idempotency-Key` (UUID); replay the stored response for 24 h, `409` if the
  body differs.
- Config through environment variables only, named as in `docker-compose.microservices.yml`.
  Never hard-code hosts, ports or secrets. `GET /health` stays public and unrenamed.
- Security target: every service validates the identity-service JWT itself (RS256, JWKS from
  `JWT_JWKS_URL`, roles `Administrator|Manager|Employee`). Not implemented anywhere yet (P2.1).
- Never log tokens, passwords or salary.

## Messaging

- Publish through the transactional outbox in the same transaction as the state change.
- Consumers: one durable queue each, record `eventId` in `processed_events` in the same transaction,
  keep the highest `aggregateVersion`, a tombstone wins over lower versions.
- Three attempts, then dead-letter. Never requeue forever.

## Tests

- Unit tests `*Test` (Surefire), integration tests `*IT` (Failsafe, run by `mvn verify`),
  cooperation tests in `tests/cooperation`.
- Integration tests use real Postgres/Mongo/RabbitMQ through Testcontainers. No H2, no embedded broker.
- Domain rules are testable without Spring. No `Thread.sleep`; use Awaitility. Stub the LLM in tests.
- A bug fix starts with a failing test. Never weaken an assertion or a CI gate to get green.
- `@Disabled` must name the task ID that re-enables it.

| Task | Command (from repo root) |
|---|---|
| Unit tests, one service | `./mvnw -B -f services/<svc>/pom.xml test` |
| Unit + integration | `./mvnw -B -f services/<svc>/pom.xml verify` |
| Cooperation tests | `./mvnw -B -f tests/cooperation/pom.xml verify` |
| Frontend | `npm --prefix frontend ci && npm --prefix frontend run typecheck && npm --prefix frontend run build` |
| Infrastructure only | `docker compose -f docker-compose.microservices.yml up -d` |
| Full stack | `docker compose -f docker-compose.microservices.yml --profile app up --build` |

## Git

- Branches `feat|fix/<service>-<topic>`, `docs/<topic>`, `ci/<topic>`. Never commit to `main`.
- Conventional commits scoped by service: `feat(scheduling): reject overlapping assignments`.
- Reference task IDs from `docs/project-plan.md` (e.g. `P3.1`) in PR titles.
- An event change updates `contracts/asyncapi.yaml`, `contracts/events/catalog.md`, the payload
  schema, and producer + consumer in one PR.
- Never commit `.env`, keys or passwords; update `.env.example` instead.

## Done means

- The narrowest test command was run and its result is quoted.
- New business rule → unit test; new messaging/database code → `*IT`.
- Service README updated in the same change when behaviour or a contract changed.
- Anything skipped or unverified is said out loud.

## Context hygiene

- `docs/DLS Report-2026-10-21.md` is ~2 MB (embedded images): search it, read line ranges only.
- Skip `target/`, `node_modules/`, `*.pdf`, and the monolith archives listed above.

## Talking to the user

- Answer in the user's language (usually Danish). Code, comments, commits and docs in English.
