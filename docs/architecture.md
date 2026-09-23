# Shift Happens: System Architecture (Mandatory Assignment 1)

This document covers the architecture topics of Mandatory Assignment 1 in *Development of Large Systems*, and it is the basis for the corresponding report chapters. Items marked **[team]** are decisions the whole group still has to confirm.

| Artifact | Purpose |
|---|---|
| [`shift-happens-high-level-architecture.drawio`](shift-happens-high-level-architecture.drawio) | High-level diagram: named services, REST/GraphQL/messaging, AI, patterns |
| [`../contracts/`](../contracts) | Contracts between the services: OpenAPI, GraphQL SDL, AsyncAPI |
| [`../services/*/README.md`](../services) | Scaffold for each service: owns / provides / publishes / consumes / done-when |
| [`../docker-compose.microservices.yml`](../docker-compose.microservices.yml) | Development environment for the target system |

The current `docker-compose.yml` still runs the monolith, which the services are extracted from.

---

## 1. Problem and requirements

**Problem.** Shift-based businesses plan staff in spreadsheets and chat threads. Nobody sees in one place who works when, who is on leave and which shifts are short-staffed. Shift Happens lets managers plan shifts and handle leave and shift swaps. Employees see their schedule and request leave or swaps, and the system keeps everyone informed.

### Functional requirements
| # | Requirement | Service |
|---|---|---|
| F1 | Users log in with email/password or with Google/GitHub; roles are Administrator, Manager and Employee | Identity |
| F2 | Administrators manage employees, contracts, departments, job roles and locations | Workforce |
| F3 | Managers create shifts with required job roles and assign qualified employees | Scheduling |
| F4 | Employees see their own schedule; managers see the full schedule and understaffed shifts | Scheduling (GraphQL) |
| F5 | An employee can ask to swap a shift with a colleague; a manager approves or rejects it | Scheduling |
| F6 | An employee requests leave; a manager approves or rejects it; the balance is tracked in an immutable ledger | Leave |
| F7 | Approved leave releases the employee's overlapping shifts. If a shift is locked, the approval is rolled back | Leave + Scheduling (saga) |
| F8 | When a shift becomes understaffed, the system suggests ranked replacements using AI | AI + Scheduling |
| F9 | Users get in-app and email notifications about assignments, swaps, leave decisions and suggestions | Notification + email-function |
| F10 | Administrators can trace every business event (who, what, when) | Audit |

### Non-functional requirements
| # | Requirement | How the architecture supports it |
|---|---|---|
| N1 | **Scalability:** read traffic (schedules) far exceeds writes | CQRS read models in MongoDB; query and command roles scale independently; competing consumers on queues |
| N2 | **Availability:** a failing service must not stop the others | Only asynchronous service-to-service communication; durable queues buffer while a consumer is down |
| N3 | **Consistency:** no lost or duplicated business effects | Transactional outbox, idempotent consumers, `Idempotency-Key` on POST, saga with compensation |
| N4 | **Security:** authenticated and role-authorized access | JWT (RS256) validated in every service; OIDC with Google/GitHub; TLS at the edge; databases on internal networks |
| N5 | **Portability:** runs the same everywhere | Every service is a container; Compose for development, Kubernetes for production-like |
| N6 | **Observability:** follow one user action across services | Correlation ID on every request and event; OpenTelemetry traces, logs and metrics |
| N7 | **Interoperability and evolvability** | Versioned REST (`/v1`), GraphQL schema, AsyncAPI-documented events with versioned types |
| N8 | **Performance:** schedule page < 500 ms at p95 | Pre-built read documents fetched in one GraphQL query |

## 2. Project management **[team]**

Proposal: **Scrum-lite with 2-week sprints** on a GitHub Projects board (Backlog → In progress → Review → Done).
- Every change goes through a pull request with at least one reviewer; `main` is protected and requires green CI.
- Issues are labelled with the service they touch, and contract changes are labelled `contract`.
- Short sync twice a week and a sprint review/retro at the end of each sprint.

## 3. Technology stack

| Concern | Choice | Why |
|---|---|---|
| Business services | Java 21, Spring Boot 3.5 | The team knows it, and the monolith already uses it, so extraction reuses code |
| REST / GraphQL | Spring Web + springdoc-openapi, Spring for GraphQL | Contract-first, generated Swagger UI |
| Messaging | RabbitMQ 4.1 (topic exchange), Spring AMQP | Routing by key, per-consumer queues, DLX, and a KEDA scaler exists |
| Write databases | PostgreSQL 17, Flyway | Transactions for commands, outbox and ledger; versioned migrations |
| Read databases | MongoDB 8 | Documents shaped for UI views, flexible and horizontally scalable |
| AI | Ollama (local LLM, `llama3.2:1b`) through Spring AI | No paid API, containerized; swappable for a hosted model |
| Identity | Spring Security + OAuth2 client (Google, GitHub), own RS256 JWT | Standard OIDC flow; one token format for all services |
| Gateway | Caddy locally; Ingress (NGINX) in Kubernetes | Routing and TLS only |
| Frontend | React 18, TypeScript, Vite; fetch for REST, Apollo Client for GraphQL | Existing frontend |
| Serverless | KEDA `ScaledJob` | Scale-to-zero jobs on queue length in local Kubernetes |
| Observability | OpenTelemetry → Prometheus, Loki, Tempo, Grafana | Vendor-neutral |
| CI/CD | GitHub Actions, Testcontainers, SonarQube Cloud, CodeQL, Trivy | Free for public repos |

## 4. Architecture

### 4.1 From workflows to services
We started from the business workflows and grouped the responsibilities that change together and own the same data.

| Workflow | Responsibilities | Service |
|---|---|---|
| Log in | Credentials, external login, tokens | **Identity** |
| Maintain staff | Employee master data, contracts, qualifications | **Workforce** |
| Plan the week | Shifts, required roles, assignments, approvals, swaps | **Scheduling** |
| Take time off | Requests, approvals, balances/ledger | **Leave** |
| Cover a gap | Rank replacement candidates | **AI** |
| Stay informed | In-app + email notifications | **Notification** (+ email-function) |
| Trace changes | Event history | **Audit** |

That gives **7 backend microservices**. The gateway, the frontend, the databases, RabbitMQ, Ollama and email-function do not count as microservices.

**Why shift swaps live inside Scheduling.** A swap moves an existing assignment. As a separate service, every swap would need a distributed workflow to update Scheduling's assignment. Inside Scheduling, it is one local transaction. The saga requirement is covered by the leave-approval flow instead, which genuinely spans two services.

### 4.2 Communication

| Channel | Style | Used for |
|---|---|---|
| Browser → gateway | HTTPS | Single entry point, TLS termination |
| Frontend → services | **REST** `/api/v1/...` | Commands on every service, plus reads for Identity, Workforce, Leave, Notification and Audit |
| Frontend → Scheduling | **GraphQL** `/api/graphql` | Schedule views: shift → assignments → employees → AI suggestions in one request |
| Service ↔ service | **RabbitMQ events** | All integration between services. **No synchronous service-to-service calls** |
| Identity ↔ Google/GitHub | OAuth 2.0 / OIDC | Third-party login |
| AI → Ollama | HTTP (Ollama API) | LLM inference |
| email-function → SMTP | SMTP | Email delivery |

**How the frontend uses both API styles.**
- Schedule pages ("My shifts", the manager's week view, understaffed shifts with AI suggestions) read through GraphQL.
- Every action is a REST call: create a shift, assign, request a swap or leave, approve.
- A typical page combines both. For example, the manager's week view loads via GraphQL, the manager assigns a suggested employee with `POST /api/v1/shiftassignments`, and the view refreshes via GraphQL.

**Why no synchronous calls between services.** Services that need another service's data keep a local copy fed by snapshot events. For example, Scheduling and Leave keep an employee replica from `workforce.employee.*`. A service therefore keeps working while Workforce is down, and there is no chain of timeouts. The cost is eventual consistency of the replica, which is typically under a second.

All contracts are in [`contracts/`](../contracts/README.md).

### 4.3 Architectural patterns

| Pattern | Where | Justification |
|---|---|---|
| **Database per service** | All services | Independent deployability; no hidden coupling through shared tables |
| **CQRS** | Scheduling (Postgres + GraphQL/Mongo), Leave (Postgres + REST/Mongo) | Reads dominate and need wide, nested views; writes need transactional rules. Separate models and scaling. Not used in Identity or Workforce, where simple CRUD does not justify the extra complexity |
| **Saga (orchestrated, with compensation)** | Leave approval: Leave ↔ Scheduling | Approving leave must release shifts in another service. A distributed transaction is not possible, so failure is handled by a compensating `REVERSAL` ledger entry and a rejection ([flow](../contracts/events/catalog.md#flow-leave-approval-saga-orchestrated-by-leave-command)) |
| **Immutable data: snapshot pattern** | Events carry full entity snapshots; the Leave ledger stores a balance snapshot every 50 entries | Consumers never need to call back; balances are fast without rewriting history |
| **Immutable data: tombstone pattern** | Deletes are soft and publish `*.deleted.v1` tombstones | Consumers keep the tombstone, so a late update cannot resurrect a deleted entity |
| **Immutable ledger** | Leave | Corrections are new entries, never updates, which gives a full audit trail |
| **Idempotent operations** | `Idempotency-Key` on every REST POST; consumers dedupe by `eventId` | Retries from the browser or redelivery from RabbitMQ never double-book leave or send two emails |
| **Commutative message handlers** | All projections and replicas | The highest `aggregateVersion` wins, tombstones beat lower versions, and balances are sums. The final state does not depend on arrival order |
| **Transactional outbox** | Every producer | State change and event are committed together, so neither is lost |
| **Event-carried state transfer + pub/sub fan-out** | One queue per consumer | Adding a consumer (e.g. Audit) needs no change in the producer |
| **Serverless function** | email-function (KEDA ScaledJob) | Isolated, bursty background work that scales to zero when idle |
| Caching (planned) | JWKS cached in every service; GraphQL responses per user for 10 s (Caffeine) | Cuts identity lookups and repeated schedule queries |

### 4.4 AI use case

**Replacement suggestions for understaffed shifts.**
1. When approved leave or a cancellation leaves a shift short, Scheduling publishes `scheduling.shift.understaffed.v1` with the qualified, available candidates.
2. The AI Service asks a local LLM (Ollama) to rank them with a one-line reason each, and publishes `ai.replacement.suggested.v1`.
3. The manager sees the ranking in the schedule (GraphQL) and gets a notification, then assigns with one REST call.

Integration approach:
- The AI is accessed through the model's HTTP API, from a dedicated microservice, and triggered by messaging.
- Its output is validated: only candidate ids from the input are accepted.
- It falls back to a rule-based ranking when the model is slow or down.
- It is advisory only and never writes the schedule.

Details are in [`services/ai-service`](../services/ai-service/README.md).

### 4.5 Key decisions (short ADRs)

| Decision | Alternatives | Why |
|---|---|---|
| Gateway without business logic (Caddy/Ingress) | GraphQL BFF | Keeps the gateway out of the microservice count and avoids a bottleneck. GraphQL lives in the service that owns the data |
| GraphQL on the Scheduling read side only | GraphQL everywhere | The schedule is the only deeply nested view; elsewhere REST is simpler |
| Events only between services | REST lookups | Availability (N2) and the course requirement for asynchronous communication |
| Orchestrated saga in Leave | Choreography | One place holds the saga state, timeout and compensation, which is easier to test and explain |
| Postgres for writes, Mongo for reads | One database type | Transactions for the ledger and outbox; document reads for views |
| Local LLM (Ollama) | Hosted API | No cost, no data leaves the machine; the same client works with a hosted model later |

### 4.6 Integration points (enables parallel work)
All contracts are agreed in `contracts/` first. After that, each service can be built against:
- **Workforce events** (`employee.*`). Identity, Scheduling and Leave depend on them, so Workforce goes first or is stubbed by publishing test events.
- **The saga events** between Leave and Scheduling: `approval-started` and `leave-release.*`.
- **`shift.understaffed` → `ai.replacement.suggested`** between Scheduling and AI.
- **The JWT format and JWKS** from Identity. Until Identity exists, other services can use a test key.

## 5. Team responsibilities **[team]**

| Area | Owner (GitHub) |
|---|---|
| Identity service + OIDC, security tests | TBD |
| Workforce service | TBD |
| Scheduling service (command + GraphQL query) | TBD |
| Leave service + saga | TBD |
| AI service + Ollama | TBD |
| Notification service, email-function, Audit service | TBD |
| Frontend (REST + GraphQL client) | TBD |
| CI/CD, Kubernetes, monitoring | TBD |

Team: `Luke3520`, `jarrald`, `Jafarr1`, `drummdurum`, `ViggoBeck`. Contract changes need review from every affected owner.

## 6. Deployment considerations

**Local production-like:** Kubernetes (kind or minikube):
- Each service role becomes a Deployment + Service with readiness and liveness probes on `/health`.
- An NGINX Ingress replaces Caddy.
- The databases and RabbitMQ run as StatefulSets (or Helm charts).
- KEDA scales email-function (and optionally the queue consumers).
- HPA scales the query roles on CPU.

**Cloud (no provider decided):**
| Concern | Implication / plan |
|---|---|
| Scalability | Stateless services scale horizontally. Command and query roles scale separately. Queue consumers scale on queue length (KEDA) |
| Managed databases | Managed Postgres and a managed Mongo-compatible store remove backup and HA work. Each service gets its own instance or at least its own schema and credentials |
| Messaging | Managed RabbitMQ (e.g. CloudAMQP or Amazon MQ) or a cluster with quorum queues for high availability |
| Latency | One region close to users. Read models serve the UI. Synchronous chains between services are avoided by design |
| Geographic distribution | Not needed initially. If it is, read models can be replicated per region, and writes stay in the home region |
| Cost | Scale-to-zero functions; small instances for low-traffic services (Audit, AI). The LLM on a GPU node is the main cost, so a hosted model API may be cheaper at low volume |
| Security | Secrets in a secret manager, not in Compose. Network policies mirror the Compose networks. TLS at the Ingress |

## 7. CI/CD pipeline

GitHub Actions in the monorepo, with one workflow per service triggered by path filters (`services/<name>/**`, `contracts/**`):

1. **Build + unit tests** (JUnit 5, Mockito) with a coverage report.
2. **Static analysis:** SonarQube Cloud (quality gate), CodeQL (security), and Trivy on the image and dependencies. Contract lint: the OpenAPI, AsyncAPI and GraphQL files are validated on change.
3. **Integration tests** (Testcontainers): the service with its real Postgres/Mongo and RabbitMQ.
4. **Cooperation tests:** two or more services plus RabbitMQ in containers, e.g. the leave saga (Leave + Scheduling).
5. **Build and push the image** to GHCR, tagged with the version and the git SHA.
6. **Deploy** to the local Kubernetes cluster (manual trigger or self-hosted runner) and run a smoke test.

Any failing step fails the pipeline, and `main` only accepts green pull requests.

## 8. Testing strategy

| Level | What | Example |
|---|---|---|
| Unit | Business rules per service | Swap only allowed to a qualified colleague; ledger balance = snapshot + entries; AI output validation |
| Integration: REST/GraphQL | Controller + security + database | `POST /v1/leaverequests` returns 202 and the same response again for a repeated `Idempotency-Key` |
| Integration: messaging | Outbox → RabbitMQ → consumer | `workforce.employee.created` updates Scheduling's replica |
| Integration: database | Flyway migrations and repositories on real Postgres/Mongo | Migrations apply cleanly from empty |
| Cooperation (system-level) | ≥ 2 services + broker in containers, no UI | Leave-approval saga, both outcomes; understaffed → AI (stub LLM) → suggestion in the Scheduling read model |
| Security | Authentication and authorization | No, expired or forged JWT → 401; Employee calling a manager endpoint → 403; OIDC with an unknown email → refused |
| Static | Quality and security scanning | SonarQube Cloud, CodeQL, Trivy |

## 9. Logging and monitoring options

- **Instrumentation:** the OpenTelemetry SDK or Java agent in every service exports traces, metrics and logs to the OTel Collector, which already runs in Compose.
- **Chosen backend for local Kubernetes:** Grafana with Prometheus (metrics), Loki (logs) and Tempo (traces), installed via Helm. One `correlationId`/trace id links a user action across REST, RabbitMQ and every service.
- **What we watch:** request rate, errors and latency per service; queue depth and DLQ size (RabbitMQ Prometheus plugin); saga timeouts; the LLM fallback rate.
- **Alternatives considered:** the ELK stack (heavier), Jaeger (traces only), and managed options (Grafana Cloud, Datadog, cloud-native monitors). Because the collector is vendor-neutral, switching only means changing its exporter.

## 10. Documentation strategy

- **Contracts first:** `contracts/` (OpenAPI → Swagger UI per service via springdoc, GraphQL SDL + GraphiQL, AsyncAPI → generated HTML docs in CI).
- **README per service** (owns, APIs, events, how to run and test), plus the monorepo README with onboarding and install steps.
- **Architecture:** this document, the draw.io diagram, and short ADRs for major decisions.
- **GitHub Wiki** for how-tos (local Kubernetes setup, troubleshooting) **[team]**.
- Docs change in the same pull request as the code or contract they describe.

## 11. Versioning strategy

| What | Strategy |
|---|---|
| Source code | Git monorepo, trunk-based with short-lived feature branches and PRs; semantic-version tags per service (`scheduling-service/v1.2.0`) |
| Container images | Tagged with the semver and the git SHA; never `latest` in Kubernetes |
| Databases | Flyway migrations per service (`V1__init.sql`, …), forward-only and run at startup; Mongo read models rebuilt from events |
| REST APIs | URL versioning `/v1`; additive changes stay in v1; breaking changes get `/v2` alongside it |
| GraphQL | No versions: add fields, deprecate old ones with `@deprecated` |
| Events | Version in `eventType` (`.v1`); a breaking change publishes `.v2` in parallel until all consumers have migrated |

## 12. Running it locally

```bash
docker compose -f docker-compose.microservices.yml --profile app --profile ai config --quiet  # validate
docker compose -f docker-compose.microservices.yml up -d                                     # infrastructure only
docker compose -f docker-compose.microservices.yml --profile app --profile ai up --build     # everything
```

App `https://localhost:8443` · RabbitMQ `http://localhost:15672` · Mailpit `http://localhost:8025`.

For Google/GitHub login, set `OIDC_GOOGLE_CLIENT_ID`/`_SECRET` or `OIDC_GITHUB_CLIENT_ID`/`_SECRET` in a local `.env` (never commit it). Local email/password login works without them.

**Migration order (strangler):**
1. Contracts.
2. Identity + Workforce.
3. Scheduling.
4. Leave + saga.
5. AI.
6. Notification, email-function and Audit.
7. Remove the monolith routes.

Until a service exists, its gateway route can point at the monolith.
