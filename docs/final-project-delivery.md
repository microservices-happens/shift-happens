# Final delivery plan — what is left and how to prove it

Status: 2026-11-20. Companion to [`project-plan.md`](project-plan.md) (task IDs) and
[`architecture.md`](architecture.md) (the design). This document is the checklist for the last stretch: it
lists what is now implemented, the exact evidence for every course requirement, what the team still has to
do (mostly report, presentation and environment chores), and a 10-minute demo script for the exam.

## 1. What changed on 2026-11-20 (this delivery)

| Area | Change | Verification |
|---|---|---|
| notification-service (P4.3) | Consumer over the 14 routing keys of `notification.events`; version-aware employee replica with tombstones; `processed_event` idempotency; in-app notifications; outbox → `notification.email.requested.v1`; REST `GET /v1/notifications?unreadOnly=` and `PATCH /v1/notifications/{id}` (owner only); JWT validation; correlation-id filter | `mvn -B -f services/notification-service/pom.xml verify` → 8 unit + 5 IT, green |
| audit-service (P4.5) | Consumer of every catalogued business event into an append-only table (unique `event_id`); `GET /v1/auditlogs` with `eventType`, `aggregateId`, `correlationId`, `from`, `to`; Administrator only; JWT validation | `mvn -B -f services/audit-service/pom.xml verify` → 1 contract + 2 IT, green |
| email-function (P4.4) | Real SMTP sender (Mailpit locally) over queue `email.send`; 3 attempts then `email.send.dlq`; in-memory dedupe per `eventId`; exits when idle (`EMAIL_FUNCTION_EXIT_WHEN_IDLE`, set by the KEDA ScaledJob) | `mvn -B -f functions/email-function/pom.xml verify` → 2 IT against RabbitMQ + Mailpit, green |
| leave-service security (#88, P1.7/P6.6) | The missing JWT validation in both CQRS roles; role rules; an Employee may only submit their own leave request; correlation-id filter | `mvn -B -f services/leave-service/pom.xml verify` → 94 unit + 196 IT, green |
| Local OIDC provider (P2.3/P5.3) | `local` provider backed by Keycloak with a realm import (`docker/microservices/local-oidc/realm.json`); same authorization-code + PKCE code path as Google; frontend button; contract, compose and README updated | `mvn -B -f services/identity-service/pom.xml verify` (78 unit + 34 IT, green) and the browser walkthrough in §5 |
| Autoscaling manifests (P7.3) | `kubernetes/autoscaling/hpa.yaml` + `keda.yaml`; the ScaledJob now exits when idle | `kubectl apply --dry-run=client -f kubernetes/autoscaling/` |
| Frontend | API/GraphQL types regenerated; typecheck, ESLint and production build pass. Added the Administrator-only **Audit Log** page (filters + envelope viewer, nav hidden for Manager/Employee). Fixed a real race found in the browser: the OIDC callback stored the JWT and navigated in the same tick, so the first dashboard requests went out with the previous (null) token, got 401 and logged the user out. The API client now reads the token from `localStorage` at request time (`AuthContext`). | `npm --prefix frontend run check:api && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run build`; browser runs in §5 |



## 2. Requirement traceability (what to point at in the report)

| Course requirement | Implementation | Evidence to cite |
|---|---|---|
| ≥ 5 backend microservices | identity, workforce, scheduling (×2 roles), leave (×2 roles), ai, notification, audit | `services/*`, Compose `app` profile |
| Frontend consumes REST **and** GraphQL | `frontend/src/api` (REST, generated types) and `frontend/src/graphql` (Apollo, generated operations) | `frontend/src/api/generated`, `frontend/src/graphql/generated.ts` |
| JWT + third-party OIDC/OAuth | identity RS256 JWT; Google, GitHub **and local Keycloak** with authorization code + PKCE | `OidcIT`, `OidcLoginServiceTest`, `LocalOidcClient`, §5 |
| AI inside a backend workflow | scheduling publishes `shift.understaffed` → ai-service → Ollama/rule fallback → suggestion in the GraphQL read model | `AiServiceApplicationIT`, `SchedulingAiIT` |
| Async communication only | RabbitMQ topic exchange; no synchronous service-to-service call | `contracts/asyncapi.yaml`, ADR 0001 |
| Database per service, no shared DB | one Postgres per service, Mongo per query role, separate credentials and networks | Compose, `kubernetes/*-db` |
| CQRS (justified) | scheduling and leave command/query roles | `docs/measurements/cqrs-read-model.md`, `CqrsReadModelBenchmarkIT` |
| Tombstone and snapshot | soft deletes + `*.deleted.v1`; snapshot payloads; balance snapshots | consumer ITs, `contracts/events/payloads` |
| Idempotent operations | `Idempotency-Key` on POST, `processed_event` per consumer, unique `event_id` in audit | `IdempotencyServiceTest`, `NotificationServiceIT`, `AuditServiceIT` |
| Commutative handlers | version-based upserts, shuffled-order tests, ledger sums | `*ShuffledOrderTest`, `LeaveProjectionReplayIT` |
| Saga with compensation | leave approval → scheduling release → reversal/rejection on timeout | `LeaveApprovalSagaIT`, `LeaveApprovalSagaConcurrencyIT` |
| Logging/monitoring | correlation id in MDC everywhere; OTel collector; OpenAPI/AsyncAPI docs | `CorrelationIdFilterTest` per service, `docs/ci-demo.md` |
| Containerized + deployment architecture | Dockerfile per service, Compose, Kubernetes manifests (Docker Desktop path) and Kustomize scaffold | `docker-compose.microservices.yml`, `kubernetes/`, `k8s/` |
| CI/CD with unit, integration, cooperation tests | `ci.yml`, `system-tests.yml`, `codeql.yml` | Actions runs, `docs/ci-demo.md` |
| Serverless function | email-function as a KEDA ScaledJob on `email.send` | `kubernetes/autoscaling/keda.yaml`, `EmailFunctionIT` |
| Versioning (git, DB, REST) | short-lived branches, Flyway `V*__`, `/v1` paths, `.vN` events | git history, migrations |
| Unit tests per service | domain rules in every service, including the new three | Surefire counts |
| Integration: API, messaging, database | Testcontainers ITs in every service | Failsafe counts |
| Cooperation test with containers | Leave + Scheduling + RabbitMQ; Scheduling + AI | `tests/cooperation` |
| Security testing | 401/403 matrices, OIDC unknown email, idempotency replay; new: leave, notification, audit | `SecurityMatrixTest`, `SecurityRulesTest`, `LeaveSecurityIT`, `NotificationServiceIT`, `AuditServiceIT` |
| Static analysis in CI | CodeQL, Trivy, ESLint, SonarQube job (needs token) | `ci.yml` |
| Docs: READMEs, Swagger, GraphQL, AsyncAPI | per-service READMEs, springdoc contract UI, GraphiQL, AsyncAPI HTML artifact | `services/*/README.md`, `contracts/` |


## 3. What the team still has to do

Ordered by what blocks the delivery, not by effort.

| # | Task | Owner | Notes |
|---|---|---|---|
| 1 | Report chapters 1–2 and 4–8 (P0.8, P9.1): assemble from the traceability table above and the service READMEs | all | The technical content exists; write with references to diagrams and tests, not code listings |
| 2 | Presentation (P0.9) built from §5 | one person | 10 minutes, live demo from a laptop with the stack already running |
| 3 | `SONAR_TOKEN` + project variables so the SonarQube gate stops skipping (P1.5/P6.7) | repo admin | `ci.yml` job already exists |
| 4 | Branch protection on `main` with the `ci` aggregate check (P1.4) | repo admin | Screenshot for §5.3 |
| 5 | Apply `kubernetes/` to Docker Desktop and take the §3.2 screenshots; install ingress-nginx + KEDA if time allows (P7.1) | one person | The autoscaling manifests are ready; KEDA/ingress installation is the remaining infra work |
| 6 | Prometheus/Grafana/Loki/Tempo stack and a dashboard (P7.4) | one person | Biggest remaining optional item; decide whether the report argues it from the OTel collector + design or installs it |
| 7 | Semver tags `<service>/v1.0.0` and a release screenshot (P7.6) | one person | `git tag scheduling-service/v1.0.0` and push |
| 8 | Publish `docs/wiki/` in the GitHub Wiki (P8.6) and finish the installation procedure in the root README (P8.5) | one person | The README already has the Compose and seed procedure; the Kubernetes section is still thin |
| 9 | Add the new services to the report's §2.2 (purpose, APIs, events, data, logic, scaling, failure) | owners | Start from `services/notification-service/README.md`, `audit-service`, `email-function` |

## 4. Definition of done for the delivery

- [ ] Every task in §3 has a named owner and a date.
- [ ] `mvn -B -f services/<svc>/pom.xml verify` is green for all seven services and `functions/email-function`.
- [ ] `mvn -B -f tests/cooperation/pom.xml verify` is green (needs the service images built with `--build-context contracts=contracts`).
- [ ] `npm --prefix frontend run typecheck && npm --prefix frontend run build` is green.
- [ ] `docker compose -f docker-compose.microservices.yml --profile app config --quiet` is green.
- [ ] The report has chapters 1–10 with figures, references and appendices; the repository link is in WISEflow.
- [ ] The demo in §5 was rehearsed once end to end on a clean `down -v` stack.


## 5. Ten-minute demo script

Start from a clean stack (the first build is the slow part):

```bash
cp .env.example .env
# .env: PASSWORD_PEPPER=..., BOOTSTRAP_ADMIN_EMAIL=admin@shift-happens.example,
#       BOOTSTRAP_ADMIN_PASSWORD=..., SEED_DEMO_PASSWORD=Demo1234!
docker compose -f docker-compose.microservices.yml --profile app up -d --build
docker compose -f docker-compose.microservices.yml --profile app ps     # wait for (healthy)
scripts/seed-dev.sh
```

1. **Login and JWT (1 min).** Open https://localhost:8443/login and sign in as the bootstrap admin. Show the
   JWT in DevTools (`Authorization: Bearer …`) and explain that every service validates it against
   `JWT_JWKS_URL` — the gateway never authorizes.
2. **Local OIDC (2 min).** Press **Local demo (Keycloak)**. The browser goes to Keycloak on
   `http://localhost:8090`, log in as `sofie.jensen@shift-happens.example` / `Demo1234!`, and the callback
   lands on `/login/callback#token=…`. In the Keycloak admin console (`http://localhost:8090`, `admin`/`admin`)
   show the client with PKCE S256 and the redirect URI. Point out that Google/GitHub use the same code path
   (`OidcIT`) and are enabled only by their `OIDC_*` client ids; the local provider is a real OIDC provider,
   not a mock inside the service.
3. **Schedule reads over GraphQL (1 min).** `/my-schedule` and `/shifts` show data from scheduling-query
   (Mongo read model); the network tab shows one `POST /api/graphql`, not one REST call per widget.
4. **Idempotency and saga (3 min).** Create a leave request twice with the same `Idempotency-Key` (see
   `docs/ci-demo.md`) and show the stored first response. Approve a leave request as a manager: the request
   goes `APPROVAL_PENDING` → `APPROVED`, and the overlapping assignment is released
   (`RELEASED_FOR_LEAVE`). If the shift is locked, the saga compensates and the request becomes `REJECTED`
   with a reason.
5. **Notifications and email (1 min).** The bell shows the notifications from those events; Mailpit
   (http://localhost:8025) shows the emails sent by email-function. Point out the outbox and the queue
   (RabbitMQ UI, http://localhost:15672).
6. **Audit (1 min).** As the Administrator open **Audit Log** in the sidebar (or call
   `GET https://localhost:8443/api/v1/auditlogs?correlationId=<one from the saga>`): the whole saga appears
   under one correlation id, the envelope can be expanded, and the log is append-only and Administrator-only
   (the nav item is hidden for a Manager and the page refuses without a request).
7. **AI (1 min).** Make a shift understaffed (release an assignment) with the `ai` profile running; show the
   ranked suggestion in the understaffed panel and the `source: llm`/`rule` fallback.
8. **Tests and CI (the last slide, no live run).** Show the Actions runs: unit + integration per service,
   cooperation tests, CodeQL, images.

## 6. Known limitations to state in the report (do not hide them)

- **Email dedupe is in memory** in email-function (bounded to 10 000 ids). A redelivery after a restart can
  send a second email; SMTP delivery is not transactional. The report's improvements section can propose a
  processed-events table or an idempotent provider API.
- **Manager alerts fan out to every active manager** for events without a recipient (understaffed shift, AI
  suggestion, cancelled shift). Employee snapshots carry the role but no manager-of-department relation, and
  a synchronous call to workforce is forbidden; an `employee.department` field or a department-manager
  replica would narrow it.
- **The local OIDC provider is Keycloak in development.** Production uses Google/GitHub (or any OIDC
  provider) by setting the `OIDC_*` variables; the code path is identical.
- **KEDA and ingress-nginx are prepared but not installed** in the Docker Desktop cluster (P7.1). The
  manifests and the trigger authentication are in `kubernetes/autoscaling/`.
- **SonarQube Cloud is configured but skipped** without `SONAR_TOKEN` (P1.5).
- **The frontend has no automated unit tests.** The pipeline runs typecheck, ESLint, the API/GraphQL type
  checks and the production build; end-to-end flows are verified manually and by the backend cooperation
  tests.

## 7. Verification commands (copy-paste)

```bash
mvn -B -f services/identity-service/pom.xml verify
mvn -B -f services/workforce-service/pom.xml verify
mvn -B -f services/scheduling-service/pom.xml verify
mvn -B -f services/leave-service/pom.xml verify
mvn -B -f services/ai-service/pom.xml verify
mvn -B -f services/notification-service/pom.xml verify
mvn -B -f services/audit-service/pom.xml verify
mvn -B -f functions/email-function/pom.xml verify
mvn -B -f tests/cooperation/pom.xml verify
npm --prefix frontend run check:api && npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend run build
docker compose -f docker-compose.microservices.yml --profile app config --quiet
```


## 8. Verified in this session (2026-11-20)

| Check | Command | Result |
|---|---|---|
| notification-service | `mvn -B -f services/notification-service/pom.xml verify` | 8 unit + 5 IT, green |
| audit-service | `mvn -B -f services/audit-service/pom.xml verify` | 1 contract + 2 IT, green |
| email-function | `mvn -B -f functions/email-function/pom.xml verify` | 2 IT (SMTP + DLQ), green |
| identity-service | `mvn -B -f services/identity-service/pom.xml verify` | 78 unit + 34 IT, green |
| leave-service | `mvn -B -f services/leave-service/pom.xml verify` | 94 unit + 196 IT, green |
| Cooperation | `SERVICE_IMAGE_TAG=dev mvn -B -f tests/cooperation/pom.xml verify` | 3/3, green |
| Frontend | `check:api`, `typecheck`, `lint`, `build` | all green (lint: warnings only) |
| Compose | `docker compose ... --profile app up -d --build` + `config --quiet` | 25 containers up (databases, broker, gateway, Keycloak, services, frontend); every container that defines a healthcheck is healthy |
| OIDC in a browser | Playwright against `https://localhost:8443` with the self-signed cert accepted | Keycloak login → dashboard as Sofie Jensen (Manager), REST + GraphQL `200` |
| Event chain in the running stack | leave request via the gateway | notification row, `audit_entry` row, outbox event, Mailpit message (email-function log with correlation id) |
| Notification UI | Playwright | bell shows 3 unread; `PATCH` marks one read |

The stack is left running after this session; `docker compose -f docker-compose.microservices.yml --profile app down`
stops it without deleting data, `down -v` also removes the volumes.

