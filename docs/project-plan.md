# Project Plan: Shift Happens (Development of Large Systems)

This plan breaks the final-project description into work packages and tasks. The architecture it implements is in [`architecture.md`](architecture.md), and the contracts are in [`../contracts/`](../contracts/README.md).

**How to use it:**
- Every task has an ID (e.g. `P3.4`). Create one GitHub issue per task using the *Task* template, put it on the Projects board, and reference the ID in its PR.
- `[x]` = done on the `feat/microservices-architecture-blueprint` branch; `[ ]` = to do.
- A task is **done** when its PR is merged with green CI, tests and docs.

---

## 1. Team and ownership **[team]**

Each service has one owner, who reviews every PR touching it and its contracts, and one backup.

| Area | Owner | Backup |
|---|---|---|
| identity-service + security tests | | |
| workforce-service | | |
| scheduling-service (command + GraphQL query) | | |
| leave-service + saga | | |
| ai-service + Ollama | | |
| notification-service, audit-service, email-function | | |
| frontend (REST + GraphQL + OIDC login) | | |
| CI/CD, Kubernetes, observability | | |
| Report + presentation coordination | | |

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
- [ ] P0.8 Report chapters 1–2 + first draft of 3.3, 4.1, 5 (from `architecture.md`)
- [ ] P0.9 Presentation (PowerPoint) + upload to itsLearning

### P1: Foundation (unblocks everyone)
- [x] P1.1 Monorepo layout, service skeletons that build and serve `/health`, Dockerfiles
- [x] P1.2 `docker-compose.microservices.yml` (infrastructure + all services)
- [x] P1.3 CI skeleton: per-service build/test/image/scan, contract lint, CodeQL, Dependabot
- [ ] P1.4 GitHub Projects board, branch protection on `main` (green CI + 1 review), CODEOWNERS filled in
- [ ] P1.5 SonarQube Cloud project + `SONAR_TOKEN`; enable the quality gate step in `services.yml`
- [ ] P1.6 Shared conventions in code (copied per service, no shared library): the event envelope class, the correlation-id filter, and the problem+json error handler. Write them once, then copy them
- [ ] P1.7 Security baseline in every service: `spring-boot-starter-oauth2-resource-server` validating the JWT from `JWT_JWKS_URL`, and role rules per `contracts/README.md`
- [ ] P1.8 Messaging baseline: Spring AMQP, exchange/queue/DLX declarations, transactional outbox table + relay, `processed_events` table for idempotent consumers
- [ ] P1.9 Flyway in every Postgres-backed service (`V1__init.sql`); a seed script for local data (port from the monolith)

### P2: Core services (parallel, one owner each)
**Identity**
- [ ] P2.1 Accounts table, `POST /v1/auth/login`, RS256 signing, `/.well-known/jwks.json`
- [ ] P2.2 Consume `workforce.employee.*` → create/update/disable accounts
- [ ] P2.3 OIDC login with Google and GitHub (authorization code + PKCE), with email matching → JWT redirect
- [ ] P2.4 `PUT /v1/auth/accounts/{id}/password`

**Workforce**
- [ ] P2.5 CRUD for employees, contracts, job roles, departments, locations (`openapi/workforce.yaml`)
- [ ] P2.6 Publish `workforce.employee.created/updated/deleted` (snapshot + tombstone, `jobRoleIds`)

**Scheduling**
- [ ] P2.7 Command role: shifts, required roles, assignments, approvals (REST, Postgres, outbox)
- [ ] P2.8 Employee replica from `workforce.employee.*`; qualification check on assign
- [ ] P2.9 Shift swaps: request + approval in one transaction
- [ ] P2.10 Query role: Mongo projection from `scheduling.*` + `workforce.employee.*`
- [ ] P2.11 GraphQL API (`contracts/graphql/scheduling.graphql`) with role-based filtering
- [ ] P2.12 Publish `scheduling.shift.understaffed.v1` with candidate filtering

**Leave**
- [ ] P2.13 Leave types, requests, approvals (REST, Postgres, outbox); employee replica
- [ ] P2.14 Immutable ledger (append-only; no PUT/DELETE) + balance snapshots every 50 entries
- [ ] P2.15 Query role: Mongo projection for overviews and balances

### P3: Distributed-systems patterns (must be demonstrable)
- [ ] P3.1 **Saga:** leave approval (Leave orchestrator, saga table, timeout) ↔ Scheduling release/lock rule, with compensation by `REVERSAL` + rejection
- [ ] P3.2 **Idempotent operations:** `Idempotency-Key` on every POST (store key → response for 24 h; 409 when the body differs); the frontend sends a key per user action
- [ ] P3.3 **Commutative handlers:** version-based upsert in all projections/replicas; a test that applies the same events in shuffled order and gets the same state
- [ ] P3.4 **Tombstones:** soft delete + `*.deleted.v1`; consumers keep the tombstone
- [ ] P3.5 **Snapshot pattern:** snapshot events (done by design) + ledger balance snapshots (P2.14)
- [ ] P3.6 **CQRS justification** written in the report with measurements (e.g. schedule query latency with vs without the read model)
- [ ] P3.7 Caching: JWKS cache; short-lived GraphQL response cache (optional)

### P4: AI, notification, audit, serverless
- [ ] P4.1 ai-service: consume `shift.understaffed`, call Ollama (Spring AI), validate the JSON output, fall back to rules, publish `ai.replacement.suggested`
- [ ] P4.2 Ollama in Compose (`--profile ai`) and in Kubernetes; pick the model and measure latency
- [ ] P4.3 notification-service: store notifications from events; `GET/PATCH /v1/notifications`; publish `notification.email.requested`
- [ ] P4.4 email-function: drain `email.send`, send via SMTP, exit; KEDA ScaledJob in Kubernetes
- [ ] P4.5 audit-service: append every business event; `GET /v1/auditlogs` with filters

### P5: Frontend (existing React app)
- [ ] P5.1 API base URL `/api/v1`; generated TypeScript types from the OpenAPI files (e.g. `openapi-typescript`)
- [ ] P5.2 Apollo Client for `/api/graphql`; move the schedule pages (my shifts, week view, understaffed + AI suggestions) to GraphQL
- [ ] P5.3 "Log in with Google/GitHub" buttons and a `/login/callback` route that reads the token from the URL fragment
- [ ] P5.4 `Idempotency-Key` on every POST; UI for saga states (`APPROVAL_PENDING`) and AI suggestions
- [ ] P5.5 Notifications (polling `GET /api/v1/notifications`)

### P6: Testing and quality (every test runs in CI and fails the pipeline)
- [ ] P6.1 Unit tests for the core business rules in **every** service (e.g. swap eligibility, ledger balance, saga state machine, AI output validation)
- [ ] P6.2 Integration: REST/GraphQL API test (MockMvc/WebTestClient + security)
- [ ] P6.3 Integration: messaging (Testcontainers RabbitMQ; outbox → queue → consumer)
- [ ] P6.4 Integration: database (Testcontainers Postgres/Mongo; Flyway migrations)
- [ ] P6.5 **Cooperation test** (`tests/cooperation`): Leave + Scheduling + RabbitMQ in containers, both saga outcomes; optionally Scheduling → AI (stub LLM)
- [ ] P6.6 Security tests: 401 (missing/expired/forged JWT), 403 (wrong role), OIDC with an unknown email refused, idempotency replay
- [ ] P6.7 Static analysis in CI: SonarQube Cloud quality gate, CodeQL, Trivy (images), ESLint (frontend)
- [ ] P6.8 Add the cooperation-test job to CI (after the images are built)

### P7: Kubernetes, CI/CD and operations
- [ ] P7.1 Local cluster (kind or minikube) + Helm infrastructure (`k8s/infrastructure/README.md`): ingress-nginx, KEDA, RabbitMQ, Postgres, Mongo, Ollama
- [ ] P7.2 Kustomize base/overlay working (`kubectl apply -k k8s/overlays/local`); secrets per service
- [ ] P7.3 Autoscaling: HPA on the query roles; KEDA ScaledObject on a queue consumer + ScaledJob for email-function; a demo with load
- [ ] P7.4 Observability: OTel agent in every service → Prometheus, Loki, Tempo, Grafana; dashboard (RED metrics, queue depth, DLQ, saga timeouts, LLM fallback rate)
- [ ] P7.5 CD: push images to GHCR on `main`; deploy to the local cluster (manual workflow or self-hosted runner) + smoke test
- [ ] P7.6 Semver tags per service (`<service>/vX.Y.Z`), with images tagged by version + SHA

### P8: Documentation (continuous, in the same PR as the code)
- [ ] P8.1 README for each service: purpose, APIs, events, how to run and test (skeletons exist)
- [ ] P8.2 Swagger UI per service (springdoc), served from the contract
- [ ] P8.3 GraphQL docs: GraphiQL on scheduling-query + SDL in `contracts/graphql`
- [ ] P8.4 AsyncAPI HTML generated in CI from `contracts/asyncapi.yaml` (published as a CI artifact or on GitHub Pages)
- [ ] P8.5 Monorepo README: an **installation procedure** from clone to a running Compose stack and Kubernetes (the course requires this for onboarding)
- [ ] P8.6 GitHub Wiki: how-tos (local Kubernetes, troubleshooting, adding a new event)
- [ ] P8.7 ADR for every major decision taken later

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
- [ ] P9.2 Diagrams for Compose, Kubernetes and CI/CD (in addition to the architecture diagram)
- [ ] P9.3 Final delivery: report + repo link + installation procedure

## 3. Requirements traceability

| Final-project requirement | How we meet it | Tasks |
|---|---|---|
| ≥ 5 backend microservices | 7: identity, workforce, scheduling, leave, ai, notification, audit | P2, P4 |
| Frontend client | Existing React app | P5 |
| Mostly async communication between services | RabbitMQ only, no sync calls | P1.8, ADR 0001 |
| REST **and** GraphQL to the frontend, consumed by it | REST on all services; GraphQL on scheduling-query | P2.11, P5.2 |
| JWT + third-party OIDC | Identity RS256 JWT; Google/GitHub | P2.1, P2.3, P5.3 |
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
