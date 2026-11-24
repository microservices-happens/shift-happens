# Project Plan: Shift Happens (Development of Large Systems)

This plan breaks the final-project description into work packages and tasks. The architecture it implements is in [`architecture.md`](architecture.md), the contracts are in [`../contracts/`](../contracts/README.md), and the order in which the service skeletons are filled is in [`implementation-plan.md`](implementation-plan.md).

**How to use it:**
- Every task has an ID (e.g. `P3.4`). Create one GitHub issue per task using the *Task* template, put it on the Projects board, and reference the ID in its PR.
- `[x]` = done on `main`; `[ ]` = to do. A note in *italics* names the evidence for a ticked task, or what exists and what is missing for an open one. Boxes and notes were checked against the code, tests and merged PRs on `main` on 2026-11-20. The finish line for the final delivery is in [`final-project-delivery.md`](final-project-delivery.md).

- A task is **done** when its PR is merged with green CI, tests and docs.

---

## 1. Team and ownership **[team]**

Each service has one owner, who reviews every PR touching it and its contracts, and one backup.

| Area | Owner |
|---|---|
| identity-service + security tests |`ViggoBeck`|
| workforce-service | |
| scheduling-service (command + GraphQL query) |`jarrald`|
| leave-service + saga |`Luke3520`|
| ai-service + Ollama |`drummdurum`|
| notification-service, audit-service, email-function |`Jafarr1`|
| frontend (REST + GraphQL + OIDC login) | |
| CI/CD, Kubernetes, observability | |
| Report + presentation coordination | |

Team: `Luke3520`, `jarrald`, `Jafarr1`, `drummdurum`, `ViggoBeck`. With 5 people and 7 services, some owners take two small services (for example notification + audit). **Everyone must have commits in the history**; the course checks this.

## 2. Work packages

Order of attack: **P0 → P1**, then **P2–P5 in parallel per owner**, then **P6–P7**. P8 (docs) runs continuously. P9 (report) starts after P0 and grows every sprint.

```
P0 Architecture ─► P1 Foundation ─┬─► P2 Core services ─┬─► P3 Distributed patterns ─┐
                                  │                     └─► P4 AI · notification · audit ┤
                                  └─► P5 Frontend ──────────────────────────────────────┤
                                                                                        ▼
                                     P6 Testing & quality ─► P7 Kubernetes & ops ─► P9 Report
                                     P8 Documentation (continuous)
```

### P0: Architecture (Mandatory Assignment 1)
- [x] P0.1 Business idea, functional and non-functional requirements (`architecture.md` §1)
- [x] P0.2 Service decomposition from workflows; data ownership (§4.1)
- [x] P0.3 Communication styles: REST, GraphQL, RabbitMQ (§4.2) and contracts (`contracts/`)
- [x] P0.4 Patterns and where they apply (§4.3); AI use case (§4.4); ADRs (`docs/adr/`)
- [x] P0.5 High-level diagram (`shift-happens-high-level-architecture.drawio`)
- [x] P0.6 Deployment considerations, CI/CD, testing, monitoring, documentation and versioning strategy (§6–11)
- [ ] P0.7 **[team]** Confirm requirements, stack, PM method and ownership (§1 above); register the team and public repo link in the OneDrive sheet
- [ ] P0.8 Report chapters 1–2 + first draft of 3.3, 4.1, 5 (from `architecture.md`) — *Partly: §3.3 (cloud deployment plan) and its diagrams are drafted (`report-3.3-cloud-deployment-plan.md`, `shift-happens-azure-deployment.drawio`, #124); chapters 1–2, 4.1 and 5 are still for the team.*

- [ ] P0.9 Presentation (PowerPoint) + upload to itsLearning

### P1: Foundation (unblocks everyone)
- [x] P1.1 Monorepo layout, service skeletons that build and serve `/health`, Dockerfiles
- [x] P1.2 `docker-compose.microservices.yml` (infrastructure + all services)
- [x] P1.3 CI skeleton: per-service unit/integration/image gates, contract lint, CodeQL, Dependabot, cooperation-test and frontend workflows
- [ ] P1.4 GitHub Projects board, branch protection on `main` (green CI + 1 review, required checks listed in `implementation-plan.md` §3), CODEOWNERS filled in — *Partly: the Projects board exists and CODEOWNERS is filled in (#99); `main` has no branch protection or ruleset yet.*
- [ ] P1.5 SonarQube Cloud project + `SONAR_TOKEN`; enable the quality gate step in `ci.yml` — *Partly: the `sonar` job and `sonar-project.properties` exist (#98), but the job skips because `SONAR_TOKEN` and the project variables are not set.*
- [x] P1.6 Shared conventions in code (copied per service, no shared library): the event envelope class, the correlation-id filter, and the problem+json error handler. Write them once, then copy them — *Done for every HTTP service: identity, workforce, scheduling, leave, ai, notification and audit have `CorrelationIdFilter` + MDC (#106 and this delivery). email-function has no HTTP surface.*
- [x] P1.7 Security baseline in every service: `spring-boot-starter-oauth2-resource-server` validating the JWT from `JWT_JWKS_URL`, and role rules per `contracts/README.md` — *Done: identity, workforce, scheduling, leave (both CQRS roles, #88), ai, notification and audit validate the JWT and enforce their role rules; email-function has no API. `SecurityMatrixTest`, `SecurityRulesTest`/`CommandSecurityRulesTest`, `LeaveSecurityIT`, `NotificationServiceIT`, `AuditServiceIT`.*
- [x] P1.8 Messaging baseline: Spring AMQP, exchange/queue/DLX declarations, transactional outbox table + relay, `processed_events` table for idempotent consumers — *Done: outbox + relay in workforce, identity, scheduling, leave, ai and notification; idempotent consumers (`processed_event(s)`, unique `event_id` in audit) in every consumer; DLX with 3 attempts everywhere, including email-function's `email.send.dlq`.*
- [x] P1.9 Flyway in every Postgres-backed service (`V1__init.sql`); a seed script for local data (port from the monolith) — *Done: Flyway in identity, workforce, scheduling, leave, ai, notification and audit; opt-in workforce demo seed published through the outbox (#107, `DevSeedIT`, `scripts/seed-dev.sh`).*

### P2: Core services (parallel, one owner each)
**Identity**
- [x] P2.1 Accounts table, `POST /v1/auth/login`, RS256 signing, `/.well-known/jwks.json`
- [x] P2.2 Consume `workforce.employee.*` → create/update/disable accounts
- [x] P2.3 OIDC login with Google and GitHub (authorization code + PKCE), with email matching → JWT redirect — *Google and GitHub use the same code path; a third provider `local` (Keycloak in Compose, `LocalOidcClient`) demonstrates the identical code + PKCE + ID-token validation flow without third-party accounts. `OidcIT`, `OidcDisabledProviderIT`, browser walkthrough in `final-project-delivery.md` §5.*
- [x] P2.4 `PUT /v1/auth/accounts/{id}/password`

**Workforce**
- [x] P2.5 CRUD for employees, contracts, job roles, departments, locations (`openapi/workforce.yaml`)
- [x] P2.6 Publish `workforce.employee.created/updated/deleted` (snapshot + tombstone, `jobRoleIds`)

**Scheduling**
- [x] P2.7 Command role: shifts, required roles, assignments, approvals (REST, Postgres, outbox)
- [x] P2.8 Employee replica from `workforce.employee.*`; qualification check on assign
- [x] P2.9 Shift swaps: request + approval in one transaction
- [x] P2.10 Query role: Mongo projection from `scheduling.*` + `workforce.employee.*`
- [x] P2.11 GraphQL API (`contracts/graphql/scheduling.graphql`) with role-based filtering — *Includes the batched nested resolvers (#127): the assignment/employee/suggestion fields load per level instead of per shift (P3.6).*

- [x] P2.12 Publish `scheduling.shift.understaffed.v1` with candidate filtering

**Leave**
- [x] P2.13 Leave types, requests, approvals (REST, Postgres, outbox); employee replica
- [x] P2.14 Immutable ledger (append-only; no PUT/DELETE) + balance snapshots every 50 entries
- [x] P2.15 Query role: Mongo projection for overviews and balances

### P3: Distributed-systems patterns (must be demonstrable)
- [x] P3.1 **Saga:** leave approval (Leave orchestrator, saga table, timeout) ↔ Scheduling release/lock rule, with compensation by `REVERSAL` + rejection
- [ ] P3.2 **Idempotent operations:** `Idempotency-Key` on every POST (store key → response for 24 h; 409 when the body differs); the frontend sends a key per user action — *Partly: `Idempotency-Key` replay/`409` on the POSTs of workforce, scheduling and leave (e.g. `WorkforceApplicationIT`, `IdempotencyServiceTest`); the frontend sends one key per user action and reuses it on retry (#104, `frontend/src/api/idempotency.ts`), not verified against a running backend.*
- [x] P3.3 **Commutative handlers:** version-based upsert in all projections/replicas; a test that applies the same events in shuffled order and gets the same state — *Shuffled-order tests: identity `EmployeeProjectionShuffledOrderTest`, scheduling `EmployeeReplicaShuffledOrderTest` + `SchedulingProjectionShuffledOrderIT` (#110), leave `LeaveProjectionReplayIT`.*
- [x] P3.4 **Tombstones:** soft delete + `*.deleted.v1`; consumers keep the tombstone — *`workforce.employee`, `scheduling.shift`, `scheduling.assignment` and `leave.type` publish `*.deleted.v1`; tombstones kept by the identity, scheduling and leave consumers (e.g. `IdentityApplicationIT`, `SchedulingProjectionIT`, `EmployeeReplicaIT`).*
- [x] P3.5 **Snapshot pattern:** snapshot events (done by design) + ledger balance snapshots (P2.14) — *Snapshot payloads validated against `contracts/events/payloads` in workforce and leave ITs; balance snapshots in `LeaveBalanceTest` and `LeaveLedgerIT`.*
- [x] P3.6 **CQRS justification** written in the report with measurements (e.g. schedule query latency with vs without the read model) — *`docs/measurements/cqrs-read-model.md` holds the benchmark and both CI runs; `CqrsReadModelBenchmarkIT` is opt-in (`-Dcqrs.benchmark=true`) and the batched resolvers were re-measured (#113, #127).*

- [x] P3.7 Caching: JWKS cache; short-lived GraphQL response cache (optional) — *JWKS fetched once and cached in workforce, scheduling and ai (`theJwksIsFetchedOnceAndThenCached`); no GraphQL response cache (optional, not built).*

### P4: AI, notification, audit, serverless
- [x] P4.1 ai-service: consume `shift.understaffed`, call Ollama (Spring AI), validate the JSON output, fall back to rules, publish `ai.replacement.suggested` — *Ollama is called through a hand-written client (`infrastructure/llm/OllamaClient`, ADR 0004), not Spring AI; `AiServiceApplicationIT`, `RankingTest`, `OllamaClientTest`.*
- [x] P4.2 Ollama in Compose (`--profile ai`) and in Kubernetes; pick the model and measure latency — *Compose profile `ai`, `kubernetes/ollama/`, `llama3.2:1b`, latency in `kubernetes/ollama/latency.md` (#85).*
- [x] P4.3 notification-service: store notifications from events; `GET/PATCH /v1/notifications`; publish `notification.email.requested` — *Implemented: consumer over `notification.events` (14 routing keys), version-aware employee replica with tombstone, `processed_event` dedupe, outbox → `notification.email.requested.v1`, REST `GET /v1/notifications?unreadOnly=` and `PATCH /v1/notifications/{id}` (owner only), JWT validation; `NotificationPolicyTest` + `NotificationServiceIT` (5 ITs: notification + email envelope, ownership/401/403, commutative replica + tombstone, manager fan-out).*
- [x] P4.4 email-function: drain `email.send`, send via SMTP, exit; KEDA ScaledJob in Kubernetes — *Implemented: real SMTP, ack after send, 3 attempts → `email.send.dlq`, in-memory dedupe per `eventId`, idle exit (`EMAIL_FUNCTION_EXIT_WHEN_IDLE`) for the ScaledJob; `EmailFunctionIT` against RabbitMQ + Mailpit; manifest in `kubernetes/autoscaling/keda.yaml`.*
- [x] P4.5 audit-service: append every business event; `GET /v1/auditlogs` with filters — *Implemented: append-only `audit_entry` (unique `event_id`), filters by eventType/aggregateId/correlationId/from/to, Administrator only, JWT validation; `AuditServiceIT`.*

### P5: Frontend (existing React app)
- [x] P5.1 API base URL `/api/v1`; generated TypeScript types from the OpenAPI files (e.g. `openapi-typescript`) — *`npm run check:api` is green for six contracts, and the running stack served the typed REST calls (employees, departments, leave requests, notifications) with `200` after the OIDC login.*
- [x] P5.2 Apollo Client for `/api/graphql`; move the schedule pages (my shifts, week view, understaffed + AI suggestions) to GraphQL — *Apollo + typed operations generated from `contracts/graphql/scheduling.graphql`; the dashboard issued `POST /api/graphql` and rendered the schedule from the read model.*
- [x] P5.3 "Log in with Google/GitHub" buttons and a `/login/callback` route that reads the token from the URL fragment — *Verified end to end in a browser against the local Keycloak provider: `/authorize` → Keycloak login → `/login/callback#token=…` → dashboard as Sofie Jensen (Manager); Google/GitHub share the same code path (`OidcIT`).*
- [x] P5.4 `Idempotency-Key` on every POST; UI for saga states (`APPROVAL_PENDING`) and AI suggestions — *Done: every write uses `useIdempotentAction`; the leave page polls the read model and shows `PENDING`/`APPROVAL_PENDING` until the saga settles, with a manual re-check; the understaffed panel renders the AI suggestions. A live approve/reject walk is now automated in `frontend/e2e/business-flows.spec.ts` (Playwright, 7 tests).*
- [x] P5.5 Notifications (polling `GET /api/v1/notifications`) — *The frontend polls and marks as read (#104, `NotificationBell.tsx`, `useNotifications.ts`) and the endpoint is now served by notification-service (P4.3).*

### P6: Testing and quality (every test runs in CI and fails the pipeline)
- [x] P6.1 Unit tests for the core business rules in **every** service (e.g. swap eligibility, ledger balance, saga state machine, AI output validation) — *Domain rules are covered in identity, workforce, scheduling, leave, ai and notification (`NotificationPolicyTest`). audit-service has no rule beyond append/search (its logic is proven by `AuditServiceIT`) and email-function's logic is its SMTP path (`EmailFunctionIT`).*
- [x] P6.2 Integration: REST/GraphQL API test (MockMvc/WebTestClient + security) — *API ITs with security in identity, workforce, scheduling (REST + `SchedulingGraphQlIT`), leave (both roles, #88), ai, notification (`NotificationServiceIT`) and audit (`AuditServiceIT`).*
- [x] P6.3 Integration: messaging (Testcontainers RabbitMQ; outbox → queue → consumer) — *Testcontainers RabbitMQ ITs for outbox → queue → consumer in identity, workforce, scheduling, leave, ai, notification (`NotificationServiceIT`), audit (`AuditServiceIT`) and email-function (`EmailFunctionIT`: queue → SMTP → Mailpit, DLQ).*
- [x] P6.4 Integration: database (Testcontainers Postgres/Mongo; Flyway migrations) — *Testcontainers Postgres/Mongo ITs with Flyway in every implemented service, including notification and audit (`PersistenceFoundationIT`, `SchedulingProjectionIT`, `NotificationServiceIT`, `AuditServiceIT`).*
- [x] P6.5 **Cooperation test** (`tests/cooperation`): Leave + Scheduling + RabbitMQ in containers, both saga outcomes; optionally Scheduling → AI (stub LLM) — *`LeaveApprovalSagaIT` (released and locked-shift compensation in one scenario) plus `SchedulingAiIT` and `EmployeeReplicationIT`; all three passed locally against the `:dev` images with `SERVICE_IMAGE_TAG=dev`, and `system-tests.yml` runs them in CI. The leave container now gets the test JWKS after #88.*
- [x] P6.6 Security tests: 401 (missing/expired/forged JWT), 403 (wrong role), OIDC with an unknown email refused, idempotency replay — *Done: 401/403 matrices in identity, workforce, scheduling, leave (`LeaveSecurityIT`, including "an Employee may only submit their own request"), ai, notification (owner-only PATCH) and audit (Administrator only); OIDC unknown e-mail refused in `OidcIT`; idempotency replay in workforce/scheduling/leave and the consumer dedupe ITs.*
- [ ] P6.7 Static analysis in CI: SonarQube Cloud quality gate, CodeQL, Trivy (images), ESLint (frontend) — *Partly: CodeQL, Trivy (non-blocking unless `TRIVY_EXIT_CODE=1`) and frontend ESLint (#100) run; the SonarQube Cloud job skips without `SONAR_TOKEN` (P1.5).*
- [x] P6.8 Add the cooperation-test job to CI (harness + `ServiceStartupSmokeIT` run; `LeaveApprovalSagaIT` is enabled by P3.1) — *`ServiceStartupSmokeIT` was never added; the job is `system-tests.yml` and runs `EmployeeReplicationIT`, `LeaveApprovalSagaIT` and `SchedulingAiIT`.*
- [x] P6.9 Browser end-to-end tests over the full Compose stack (Playwright) — *`frontend/e2e/business-flows.spec.ts`: login + session reload, employee + job role, shift + staffing plan + assignment, swap approval, swap rejection, leave approval and leave rejection; `npm --prefix frontend run e2e` passed 7/7 locally. The suite found and now guards the session-on-reload, shift-staffing, leave-balance and swap-UI gaps fixed in this change.*

### P7: Kubernetes, CI/CD and operations
- [ ] P7.1 Local cluster (kind or minikube) + Helm infrastructure (`kubernetes/infrastructure/README.md`): ingress-nginx, metrics-server, KEDA, RabbitMQ, Postgres, Mongo, Ollama — *Partly: Docker Desktop Kubernetes runs the plain manifests in `kubernetes/` (ADR 0005) for RabbitMQ, Postgres, Mongo, Ollama, Mailpit and the OTel collector; KEDA and metrics-server are installed, ingress-nginx is still open.*
- [x] P7.2 Plain-manifest deployment with secrets per service — *Per-service secrets are in `kubernetes/secrets/`, applied with `kubectl apply -n shift-happens -R -f kubernetes/` after `kubernetes/namespace.yaml`. The old Kustomize `k8s/` tree was removed and its namespace/Ingress ported into `kubernetes/`.*
- [ ] P7.3 Autoscaling: HPA on the query roles; KEDA ScaledObject on a queue consumer + ScaledJob for email-function; a demo with load — *Metrics-server is installed (`kubectl top` works) and the frontend HPA scaled 2 → 5 under a `hey` load test (CPU target 60%); CPU HPAs for scheduling-query/leave-query/frontend are in `kubernetes/autoscaling/hpa.yaml`. KEDA 2.17 is installed and the email ScaledJob is verified: a 500-message burst produced 5 Jobs (max), 500 Mailpit messages, each Job completed in ~12 s and the queue drained to 0. Two production bugs were fixed (SMTP env missing; idle exit blocked shutdown → `halt(0)`). The notification ScaledObject is in the same KEDA manifest and assumes the full app stack. Manual query scale-out: 2 replicas, 30,000 requests, 948.5 req/s, p95 143 ms, 0 errors.*
- [ ] P7.4 Observability: OTel agent in every service → Prometheus, Loki, Tempo, Grafana; dashboard (RED metrics, queue depth, DLQ, saga timeouts, LLM fallback rate) — *Local Compose now runs Prometheus + Loki + Promtail + Grafana + cAdvisor; all services expose Micrometer metrics at `/prometheus`, RabbitMQ at `/metrics/per-object`, and KEDA metrics are scraped through a NodePort. The provisioned **Shift Happens — Live System** dashboard shows RED, p95, JVM heap/CPU, queue/DLQ, KEDA demand and Loki logs; captured evidence is in `docs/diagrams/observability-dlq-recovery.md`. The OpenTelemetry Java agent and Tempo add distributed tracing, and Kubernetes manifests add a separate Grafana dashboard for Collector metrics, Tempo traces and Loki logs. See [`kubernetes/OBSERVABILITY.md`](../kubernetes/OBSERVABILITY.md) for setup and smoke checks. The trace demo checks one REST-to-RabbitMQ-to-AI trace with the correlation ID, and the dashboards show saga timeouts and AI fallback rate. Run the demo with the updated images, verify the Kubernetes stack and confirm email-function telemetry before closing P7.4.*
- [ ] P7.5 CD: push images to GHCR on `main`; deploy to the local cluster (manual workflow or self-hosted runner) + smoke test — *Partly: `ci.yml` pushes `:<sha>` and `:main` images to GHCR on `main`; no deploy or smoke-test step.*
- [ ] P7.6 Semver tags per service (`<service>/vX.Y.Z`), with images tagged by version + SHA

### P8: Documentation (continuous, in the same PR as the code)
- [x] P8.1 README for each service: purpose, APIs, events, how to run and test (skeletons exist) — *Full READMEs for identity, workforce, scheduling, leave, ai, notification, audit and email-function; the three former skeletons were rewritten in this delivery.*
- [x] P8.2 Swagger UI per service (springdoc), served from the contract
- [x] P8.3 GraphQL docs: GraphiQL on scheduling-query + SDL in `contracts/graphql`
- [x] P8.4 AsyncAPI HTML generated in CI from `contracts/asyncapi.yaml` (published as a CI artifact or on GitHub Pages) — *`contracts` job in `ci.yml` uploads `asyncapi-html` as a CI artifact (#112).*
- [ ] P8.5 Monorepo README: an **installation procedure** from clone to a running Compose stack and Kubernetes (the course requires this for onboarding) — *Partly: the root README now leads from prerequisites through Compose, demo data and login, and `docs/manual-testing.md` covers the day-to-day use plus the optional ports override. The Kubernetes section is still thin (P7.1/P7.2).*
- [x] P8.6 GitHub Wiki: how-tos (local Kubernetes, troubleshooting, adding a new event) — *`docs/wiki/` holds the three how-tos (#116), ready to publish in the GitHub Wiki.*
- [x] P8.7 ADR for every major decision taken later — *ADRs 0004–0006 (#102) and 0007 for the local OIDC provider. Continuous: add one per later decision.*

### P9: Report and final delivery (WISEflow)
Map each report section to its source so writing is mostly assembling:

| Report section | Source |
|---|---|
| 1 Introduction, requirements, stack | `architecture.md` §1, §3 |
| 2.1 Architecture + diagram | §4, drawio |
| 2.2 Service descriptions | `services/*/README.md` (purpose, APIs, events, data, logic, scaling/failure) |
| 2.3 Communication | §4.2, `contracts/README.md` |
| 2.4 Patterns (CQRS, immutability, idempotence, commutative, saga, caching) | §4.3 + P3 results/measurements |
| 3 Environments (Compose, Kubernetes, CI/CD, monitoring, autoscaling, cloud plan) | P1, P7, §6 |
| 4 Testing | P6 + CI screenshots |
| 5 Project management, versioning, docs | §2, §10, §11, board history |
| 6–8 Discussion, reflection, conclusion | Team retro notes kept every sprint |
| 9 References | Keep a shared bibliography from day one (official docs, books, papers) |

- [ ] P9.1 Report skeleton with the required structure (cover, lists, TOC, sections 1–10)
- [x] P9.2 Diagrams for Compose, Kubernetes and CI/CD (in addition to the architecture diagram) — *`docs/diagrams/` (Compose, Kubernetes, CI/CD) added in #118; the cloud plan diagrams are in `docs/shift-happens-azure-deployment*.drawio`.*
- [ ] P9.3 Final delivery: report + repo link + installation procedure

## 3. Requirements traceability

| Final-project requirement | How we meet it | Tasks |
|---|---|---|
| ≥ 5 backend microservices | 7: identity, workforce, scheduling, leave, ai, notification, audit | P2, P4 |
| Frontend client | React app consuming REST **and** GraphQL, OIDC login | P5 |
| Mostly async communication between services | RabbitMQ only, no sync calls | P1.8, ADR 0001 |
| REST **and** GraphQL to the frontend, consumed by it | REST on all services; GraphQL on scheduling-query | P2.11, P5.2 |
| JWT + third-party OIDC | Identity RS256 JWT; Google, GitHub and a local Keycloak provider | P2.1, P2.3, P5.3 |
| AI via an API inside a backend workflow | ai-service + Ollama, understaffed → suggestion | P4.1–P4.2 |
| Independently deployable; database per service | Separate builds/images/DBs; no shared DB | P1.1, P1.9 |
| CQRS (justified) | Scheduling, Leave | P2.10, P2.15, P3.6 |
| Tombstone and/or snapshot | Both | P3.4, P3.5 |
| Idempotent operations | Idempotency-Key + eventId dedupe | P3.2, P1.8 |
| Commutative handlers | Version-based upserts, ledger sums | P3.3 |
| Saga with compensation | Leave approval | P3.1 |
| Logging + monitoring | OTel + Grafana stack | P7.4 |
| Containerized, clear deployment architecture | Dockerfiles, Compose, Kubernetes | P1, P7 |
| CI/CD with unit, integration and cooperation tests | GitHub Actions | P1.3, P6 |
| Local LLM allowed | Ollama | P4.2 |
| Serverless function | email-function as a KEDA ScaledJob | P4.4, P7.3 |
| Versioning: git, DB migrations, REST API | Semver tags, Flyway, `/v1` | P7.6, P1.9 |
| Unit tests per service | Core business logic | P6.1 |
| Integration: API, messaging, database | Testcontainers | P6.2–P6.4 |
| Cooperation test with containers | Leave saga | P6.5 |
| Security testing | Auth/authz tests | P6.6 |
| Static analysis in CI | SonarQube Cloud, CodeQL, Trivy | P6.7 |
| Docs: READMEs, Swagger, GraphQL, AsyncAPI, overall | contracts + generated docs | P8 |
| Dev env with Compose; prod-like local Kubernetes | Both | P1.2, P7.1–P7.2 |
| Installation procedure | Monorepo README | P8.5 |

## 4. Suggested sprints (2 weeks) **[team]**

| Sprint | Goal | Main tasks |
|---|---|---|
| 1 | Mandatory 1 delivered; foundation ready | P0.7–P0.9, P1.4–P1.9 |
| 2 | Every core service does its CRUD and publishes events | P2.1–P2.2, P2.5–P2.7, P2.13, P5.1 |
| 3 | Read sides, GraphQL, OIDC; first cooperation test | P2.3, P2.8–P2.12, P2.14–P2.15, P5.2–P5.3, P6.5 |
| 4 | Saga, idempotency, AI, notifications | P3.1–P3.5, P4.1–P4.5, P5.4–P5.5 |
| 5 | Kubernetes, autoscaling, monitoring; test and quality gates | P6, P7 |
| 6 | Documentation, measurements, report, presentation | P3.6, P8, P9 |

## 5. Risks

| Risk | Mitigation |
|---|---|
| Services built against different assumptions | Contracts first (`contracts/`), contract lint in CI, owners review contract PRs |
| Saga/messaging bugs found late | Build the cooperation test in sprint 3, not at the end |
| LLM too slow on laptops or in CI | Small model (`llama3.2:1b`), rule fallback, stub LLM in tests |
| Kubernetes setup eats a sprint | One person owns P7 from sprint 2; start with kind + Helm defaults |
| Uneven git contributions | Ownership table; everyone owns at least one service and one cross-cutting task |
| Report written at the end | Map sections to sources (P9) and write a little every sprint |
