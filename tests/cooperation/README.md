# System-level cooperation tests

These tests start **two or more real services plus RabbitMQ (and their databases) in containers**. They verify that the services cooperate via messaging. They do not test UI or end-to-end user flows.

Planned module layout (a Maven test-only project, built in CI after the service images):

```
tests/cooperation/
├── pom.xml                         # junit5, testcontainers (rabbitmq, postgresql, mongodb), awaitility
└── src/test/java/.../cooperation/
    ├── support/                    # starts service images from GHCR/local tags on one Docker network
    ├── LeaveApprovalSagaIT.java    # leave + scheduling: completed path and compensation path
    ├── ReplacementSuggestionIT.java# scheduling + ai (stub LLM): understaffed → suggestion in read model
    └── EmployeeReplicationIT.java  # workforce → identity/scheduling replicas (incl. tombstone)
```

The minimum required by the course is one test: `LeaveApprovalSagaIT`, covering both saga outcomes.
