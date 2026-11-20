# System-level cooperation tests

These tests start **two or more real service containers plus RabbitMQ** on one Docker network and
verify that the services cooperate, normally through messaging. They do not test UI or end-to-end
user flows (course requirement, §5 of the final project description).

## Layout

```
tests/cooperation/
├── pom.xml                                  # JUnit 5, Testcontainers, AssertJ, Awaitility
└── src/test/java/dk/ek/shift_happens/cooperation/
    ├── support/ServiceContainer.java        # starts services/<name> images on a shared network
    ├── EmployeeReplicationIT.java           # workforce → RabbitMQ → identity (enabled)
    └── LeaveApprovalSagaIT.java             # leave saga, both outcomes (enabled)
```

`EmployeeReplicationIT` is the first system-level cooperation test: it posts an employee to
workforce-service, and asserts that identity-service consumed `workforce.employee.created.v1` and
created the account. It starts RabbitMQ and one PostgreSQL per service, and reads each service's own
database for the assertions — no mocks and no non-contract endpoints.

`LeaveApprovalSagaIT` is the second one (P3.1): leave-service and scheduling-service, each with its own
PostgreSQL, plus RabbitMQ. The employees reach both replicas as `workforce.employee.created.v1` events
(published through the RabbitMQ management API), a manager approves a leave request through the Leave REST
API, and the test asserts both saga outcomes in the services' own databases: the completed path (the
overlapping assignment is `RELEASED_FOR_LEAVE`, the request `APPROVED`, `leave.request.approved.v1`
published) and the compensation path (a shift that starts within 24 hours is LOCKED, so Scheduling answers
`rejected`; Leave appends a `REVERSAL` and the request is `REJECTED`).

## Run it

```bash
# 1. Build the images the tests use (CI does this automatically)
docker build --build-context contracts=contracts -t shift-happens/identity-service:ci services/identity-service
docker build --build-context contracts=contracts -t shift-happens/workforce-service:ci services/workforce-service
docker build --build-context contracts=contracts -t shift-happens/leave-service:ci services/leave-service
docker build --build-context contracts=contracts -t shift-happens/scheduling-service:ci services/scheduling-service

# 2. Run
mvn -B -f tests/cooperation/pom.xml verify
```

| Environment variable | Meaning |
|---|---|
| `SERVICE_IMAGE_TAG` | Image tag to run (default `ci`) |
| `COOPERATION_REQUIRE_IMAGES` | `true` makes a missing image fail instead of skip. CI sets this; without it the test is skipped locally so a fresh clone does not fail. |

Failsafe only runs `*IT` classes, so `mvn test` is a fast compile check while `mvn verify` runs the
containers.

## Adding a cooperation test

1. Add the service to the `matrix.service` list in `.github/workflows/system-tests.yml` so CI
   builds its image.
2. Start it with `ServiceContainer.create("<service>", network)` and any database container it
   needs (PostgreSQL/MongoDB) on the same `Network`.
3. Drive the scenario through a real API (REST/GraphQL) and assert the cooperating service's state
   or the events it consumed. Use Awaitility for eventual consistency.
4. Never call a real LLM: stub the model or use the rule-based fallback.

