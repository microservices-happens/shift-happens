# Course requirement compliance

This document maps the requirements in `Final project description.md` to the implementation in this repository. It is the document to use when writing report sections 1-4. The 65-item product requirement audit is kept separately in [`v1.1-backlog.md`](v1.1-backlog.md).

The course requires the core distributed-system capabilities, not every item in the larger product requirement list. The product-specific list and its v1.0/v1.1 disposition are separate so the report can be precise about scope.

Status key: **Met** means implemented and demonstrable from the repository; **Partial** means the capability exists but an important evidence or deployment step remains.

## 1. Communication and integration

| Course requirement | Status | Evidence |
|---|---|---|
| Backend services primarily use asynchronous message queues | Met | RabbitMQ topic exchange `shift-happens.events`; transactional outbox in every producer; no synchronous service-to-service calls. See `contracts/asyncapi.yaml`, ADR 0001 and `docs/diagrams/async-services.md`. |
| At least one REST API | Met | Six OpenAPI contracts under `contracts/openapi/`; REST controllers in identity, workforce, scheduling, leave, notification and audit. |
| At least one GraphQL API | Met | `contracts/graphql/scheduling.graphql`; implemented by `scheduling-query` at `/graphql`. |
| Frontend consumes both REST and GraphQL and multiple services | Met | `frontend/src/api` uses generated REST types; `frontend/src/graphql` uses Apollo; scheduling reads use GraphQL and writes use REST. |
| JWT-based authentication | Met | Identity issues RS256 JWTs; every service validates against `JWT_JWKS_URL`; `LoginIT` and the per-service security tests cover 401/403. |
| Third-party OAuth/OIDC provider | Met | Google and GitHub clients in `OidcLoginService`; `OidcIT` tests both code paths. Local Keycloak is the reproducible provider for the demo. |
| AI service accessed through an API and used in a real workflow | Met | `scheduling.shift.understaffed.v1` -> ai-service -> Ollama `/api/generate` -> `ai.replacement.suggested.v1`; the manager sees ranked candidates in the schedule UI. `SchedulingAiIT` covers the cooperation path. |

## 2. Architecture, ownership and patterns

| Course requirement | Status | Evidence |
|---|---|---|
| At least five independently deployable backend microservices | Met | Seven: identity, workforce, scheduling, leave, ai, notification, audit. Each has its own `pom.xml`, Dockerfile and image. |
| Each service owns its data and does not read another service database | Met | Separate Postgres instances and separate Mongo read databases; services keep event-fed replicas. See the Compose and Kubernetes database manifests. |
| CQRS where appropriate, justified | Met | Scheduling and leave have command/query roles; `CqrsReadModelBenchmarkIT` and `docs/measurements/cqrs-read-model.md` justify the split by availability/isolation, not by query latency. |
| Immutable data with tombstone and/or snapshot | Met | `*.deleted.v1` tombstones; full snapshots in event payloads; immutable leave ledger. |
| Idempotent operations | Met | `Idempotency-Key` on POST; `processed_event` per consumer; unique `event_id` in audit; replay tests. |
| Commutative message handlers | Met | Version-based upserts, tombstones win over lower versions, ledger balances are sums; shuffled-order and replay tests. |
| Saga with compensating actions | Met | Leave approval orchestrates scheduling release; a locked shift causes a REVERSAL ledger entry and rejection; `LeaveApprovalSagaIT` covers success, rejection, timeout and retry. |

## 3. Cloud-native and operational concerns

| Course requirement | Status | Evidence |
|---|---|---|
| Logging and monitoring suitable for distributed systems | Met | Every service has Micrometer `/prometheus`; Prometheus, Loki, Promtail, Grafana and cAdvisor run in Compose; the provisioned dashboard covers RED metrics, queues, DLQ, JVM and KEDA. Correlation IDs are in MDC and logs. Distributed tracing is **not** required by this course description and is still future work. |
| Containerized services | Met | Dockerfile per service, frontend and email-function; Compose `app` profile. |
| Clear deployment architecture | Met | `docker-compose.microservices.yml`, `kubernetes/` plain manifests, README installation procedure and architecture diagrams. |
| CI/CD runs unit, integration and system-level cooperation tests | Met, with a gate caveat | `ci.yml` runs Surefire + Failsafe Testcontainers tests for every changed service. `system-tests.yml` builds real service images and runs `tests/cooperation` automatically on main, nightly and manual runs. The separate `system-tests` workflow is not a dependency of the aggregate `ci` check; see the action below. |
| Local LLM is allowed | Met | Ollama `llama3.2:1b` in Compose/Kubernetes; AI times out to a rule-based ranking. |
| Serverless function | Met | `email-function` consumes `email.send` as a KEDA ScaledJob; `EmailFunctionIT` and `kubernetes/autoscaling/keda.yaml`. |

## 4. Versioning strategy

| Course requirement | Status | Evidence |
|---|---|---|
| Source code versioning | Met | Git/GitHub monorepo, branches, conventional commits, PR template; history is the contribution evidence. |
| Database versioning | Met | Flyway migrations per Postgres service; Mongo read models are rebuildable from events. |
| REST API versioning | Met | Every REST path is under `/v1`; the gateway exposes `/api/v1`; breaking changes use `/v2` according to `contracts/README.md`. |

## 5. Testing requirements

| Course requirement | Status | Evidence |
|---|---|---|
| Automated unit tests per service | Met | Domain tests under every `services/*/src/test/java`; Surefire is part of `mvn verify`. |
| Integration tests for API, messaging and database | Met | Failsafe `*IT` tests use Testcontainers for PostgreSQL, MongoDB and RabbitMQ; covers REST/GraphQL, outbox and consumer behavior. |
| Security testing | Met | 401/403 matrices, expired/forged tokens, OIDC unknown email, idempotency replay and ownership tests. |
| Cooperation test between two or more services | Met | `tests/cooperation`: EmployeeReplicationIT, LeaveApprovalSagaIT and SchedulingAiIT start real service containers plus RabbitMQ. |
| Static analysis in CI/CD | Met | CodeQL workflow; Trivy image scan (blocking when `TRIVY_EXIT_CODE=1`); SonarQube job when `SONAR_TOKEN` is configured. |
| Test failures fail the pipeline | Met, with a gate caveat | Surefire/Failsafe failures fail `ci.yml`; cooperation-test failures fail `system-tests.yml`. Branch protection should require both `ci` and `system-tests` on main. |

## 6. Documentation requirements

| Course requirement | Status | Evidence |
|---|---|---|
| Monorepo README, service READMEs and wiki | Met | Root `README.md`, `services/*/README.md`, `docs/wiki/`. |
| Swagger/OpenAPI for all REST APIs | Met | `contracts/openapi/*.yaml`; springdoc Swagger UI in each REST service; contracts are linted in CI. |
| GraphQL schema and endpoint documentation | Met | `contracts/graphql/scheduling.graphql`; GraphiQL in development; `docs/manual-testing.md`. |
| AsyncAPI for all messaging | Met | `contracts/asyncapi.yaml`, queue and binding documentation in `contracts/events/catalog.md`, JSON Schemas, AsyncAPI HTML artifact in CI. |
| Overall architecture, communication and patterns | Met | `docs/architecture.md`, `docs/diagrams/`, ADRs and service READMEs. |

## 7. Environment requirements

| Course requirement | Status | Evidence |
|---|---|---|
| Development environment with Docker Compose | Met | `docker-compose.microservices.yml` plus `docker-compose.ports.yml`; `scripts/seed-dev.sh`. |
| Production-like local Kubernetes | Partial | `kubernetes/` contains the manifests and KEDA/metrics-server/HPA configuration. The full application stack has not been applied and smoke-tested end to end in the local cluster yet; KEDA, the email ScaledJob, metrics-server and the frontend HPA have been verified separately. |
| Cloud deployment plan | Met | `docs/report-3.3-cloud-deployment-plan.md` covers AKS, availability zones, managed databases, scaling and security. |

## Course-level actions before delivery

These are evidence/operations actions, not new product features:

1. **Make the cooperation workflow a required check.** In GitHub branch protection, require `system-tests` in addition to `ci` on `main`, or document that main is protected by both workflows. `system-tests.yml` already runs automatically on `main` and nightly.
2. **Apply the full Kubernetes stack once.** Run `kubectl apply -f kubernetes/namespace.yaml` and `kubectl apply -n shift-happens -R -f kubernetes/`, then capture `kubectl get pods`, `kubectl get hpa` and one login/schedule/leave-read result. The primary entry point is the `api-gateway` NodePort `30443`; ingress-nginx is optional.
3. **If ingress-nginx is used**, fix or avoid the optional `kubernetes/ingress.yaml` leave route: it currently sends all leave paths to `leave-command`, including GETs. The supported Caddy `api-gateway` path already routes GETs to `leave-query`.
4. **Optional static-analysis evidence:** set `SONAR_TOKEN` and `TRIVY_EXIT_CODE=1` in repository variables; CodeQL and the test gates already run without them.

## What not to claim in the report

The course requirements above are satisfied by the core design, but the following statements would be too strong:

- Do not claim that all 65 product requirements are met; see [`v1.1-backlog.md`](v1.1-backlog.md).
- Do not claim 99.9% availability or production HA from the local Docker Desktop stack.
- Do not claim distributed tracing; Prometheus/Loki/Grafana and correlation IDs are the implemented observability evidence.
- Do not claim the full Kubernetes stack is deployed until the apply/smoke-test step above is done.
