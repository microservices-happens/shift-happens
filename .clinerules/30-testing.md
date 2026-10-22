# Testing rules

Three levels; all of them run in CI and a failure must fail the pipeline.

| Level | Location | Naming | Runner |
|---|---|---|---|
| Unit | `services/<svc>/src/test/java` | `*Test`, `*Tests` | Surefire: `mvn test` |
| Integration | `services/<svc>/src/test/java` | `*IT` | Failsafe: run by `mvn verify` |
| Cooperation (system) | `tests/cooperation` | `*IT` | `mvn -f tests/cooperation/pom.xml verify` |

## Unit tests

- JUnit 5 + AssertJ; Mockito only for collaborators at the edge of the domain.
- Domain rules must be testable without a Spring context.
- Run fast and deterministically: no sleeps, no network, fixed clocks.

## Integration tests

- Real engines through Testcontainers: PostgreSQL, MongoDB, RabbitMQ. No H2, no embedded broker,
  no mocked repository.
- Cover at least: REST or GraphQL behaviour with security enabled, outbox → queue → consumer, and
  Flyway migrations against the real database.
- The service has `maven-failsafe-plugin` wired, so a class ending in `IT` runs automatically.

## Security tests

- Missing, expired or forged JWT → `401`.
- Valid token with the wrong role → `403` (e.g. an Employee calling a manager or audit endpoint).
- OIDC login with an unknown e-mail is refused.
- Replaying an `Idempotency-Key` returns the stored response; the same key with a different body
  returns `409`.

## Cooperation tests

- Start two or more real service images plus RabbitMQ (and their databases) on one Docker network.
- Assert what services exchange (events, resulting state), not UI or end-to-end user flows.
- Stub the LLM; never call a real model from CI.
- Use Awaitility for eventual consistency, never `Thread.sleep`.
- Images are built by `.github/workflows/system-tests.yml`; locally build them first with
  `docker build -t shift-happens/<service>:ci services/<service>`.
- `@Disabled` must name the task that enables it and must not survive that task.

## General

- A bug fix starts with a failing test.
- Assert behaviour that can actually regress. `assertNotNull` on everything is not a test.
- Do not weaken or delete assertions to make CI green; fix the code or raise the task.
