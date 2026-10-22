# Implementation Plan — filling the microservice skeletons

Status: 2026-10-21. Companion to [`project-plan.md`](project-plan.md) (task IDs `P#.#`) and
[`architecture.md`](architecture.md) (the target design). This document defines **the order** in
which the seven service skeletons are implemented, what each slice contains, and when a slice is
accepted.

## 1. Current state

| Area | State |
|---|---|
| Architecture and requirements | Complete: `docs/architecture.md`, `docs/adr/`, high-level diagram |
| Contracts | Complete: `contracts/openapi/*.yaml`, `contracts/graphql/scheduling.graphql`, `contracts/asyncapi.yaml`, JSON Schemas |
| Services | 7 walking skeletons: Java 21, Spring Boot 3.5.10, `GET /health`, one context test each. No domain code, no data access, no messaging |
| Email function | Walking skeleton (context test only) |
| Frontend | React 18 + Vite + TypeScript, routing only. No REST client, no Apollo, no auth |
| Local infrastructure | `docker-compose.microservices.yml` defines databases, RabbitMQ, Mailpit, OTel, gateway and all services |
| Kubernetes | Kustomize base + local overlay, HPA, Ingress, KEDA ScaledJob |
| CI | Contract lint, CodeQL, per-service unit/integration/image gates, frontend build, dependency review, cooperation-test workflow |
| Tests | One smoke test per service; cooperation harness with the saga test disabled |

"Empty" means a service has no persistence, security, AMQP or Flyway script and exposes only
`/health`. Phase 0 adds the shared conventions once; each owner then fills one vertical slice at a
time.

## 2. How we implement

1. **Contracts first — already done.** Paths, event names and payloads are fixed. A contract change
   is a separate PR reviewed by every affected owner.
2. **Vertical slices, not layers.** A slice goes controller → use case → domain → persistence →
   event → tests. It is demoable on its own and ends with a green pipeline.
3. **One service per PR**, except a contract/event change that must update producer and consumer
   together.
4. **Copy, do not share.** Conventions are copied into each service. No shared Maven parent and no
   shared library, so every service stays independently deployable.
5. **Test with the real thing.** No H2, no embedded broker: Testcontainers runs real PostgreSQL,
   MongoDB and RabbitMQ.

## 3. Phase 0 — foundations (1–2 days, unblocks everything)

Target: a feature can be built in any service with security, persistence, messaging and errors
already wired, without inventing conventions.

### P1.6 Shared conventions (one PR, then copied and adapted per service)

| Convention | Where | Acceptance |
|---|---|---|
| `EventEnvelope` record + Jackson mapping | `infrastructure/messaging` | Round-trips `contracts/events/envelope.schema.json`; unit test |
| Correlation-id filter | `api` + `config` | `X-Correlation-Id` accepted or generated, stored in MDC, copied into every event; test |
| `problem+json` error handler | `api` | Validation and domain errors return RFC 9457; test |
| Security baseline | `config/SecurityConfig` | JWKS from `JWT_JWKS_URL` (cached), roles from the `role` claim, `/health` public; 401/403 tests |
| Transactional outbox + relay | `infrastructure/persistence` + `messaging` | State change and event commit together; IT with Testcontainers RabbitMQ |
| `processed_events` dedupe | `infrastructure/persistence` | Redelivery is a no-op; IT |
| Version-aware upsert + tombstone rule | `infrastructure/persistence` | Highest `aggregateVersion` wins, tombstone wins over lower versions; shuffled-order test |
| Flyway baseline | `resources/db/migration/V1__init.sql` | `mvn verify` migrates a Testcontainers PostgreSQL |

Done when identity-service and workforce-service both pass `mvn verify` with one `*IT` that proves
security + database + outbox, and the remaining services contain the same files with their own
tables.

### P1.9 Seed data

Port departments, work locations, job roles and a few employees from
`docker/init/02-seed-data.sql` into `V2__seed.sql` (or a repeatable script). Tests must not depend
on seed data; it exists for manual demos.

### P1.4 Repository settings (one person, 10 minutes)

- Branch protection on `main`: require **`ci`** (the aggregate job in `ci.yml` always runs, even when
  no service changed), plus one review and no direct pushes. `codeql`, `system-tests` and
  `nightly-shift-risk` run on their own schedule/triggers and do not block a merge.
- Fill in `.github/CODEOWNERS` from the ownership table in §8.

## 4. Phase 1 — vertical slices

The order follows data dependencies: Workforce is the source of employee data that Identity,
Scheduling and Leave replicate.

```
Workforce ──┬─► Identity (accounts)
            ├─► Scheduling command ──► Scheduling query (GraphQL)
            └─► Leave command ──► Leave query
                     │
                     └── saga with Scheduling (Phase 2)

Scheduling ──► AI (understaffed) ──► Scheduling projection
Notification ◄── all events         Audit ◄── all events
Notification ──► email-function
```

### Slice 1 — Workforce (P2.5, P2.6)

**Why first:** it owns the employee snapshot every other service copies.

- REST CRUD for `/v1/employees`, `/v1/employeecontracts`, `/v1/employeejobroles`, `/v1/departments`,
  `/v1/jobroles`, `/v1/worklocations` exactly as in `contracts/openapi/workforce.yaml`.
- PostgreSQL + Flyway: `department`, `work_location`, `job_role`, `employee`, `employee_contract`,
  `employee_job_role`. Salary never appears in a response or an event.
- Publish `workforce.employee.created|updated|deleted.v1` from the outbox with the full snapshot
  including `jobRoleIds`. A delete is a soft delete plus a tombstone event.
- Validation: required fields, unique e-mail, no expired job-role assignment.

**Acceptance:** CRUD works through the gateway; the filters in the contract work; an expired job
role is rejected (unit test); the event validates against
`contracts/events/payloads/employee.schema.json` (IT); a late update cannot resurrect a deleted
employee (unit test).

### Slice 2 — Identity (P2.1–P2.4)

Can start in parallel with a seeded accounts table; consuming workforce events comes second.

- PostgreSQL: `account` (unique e-mail, bcrypt + pepper hash, role, active flag),
  `external_identity` (provider + subject, unique together), JWT key pair.
- `POST /v1/auth/login` returns an RS256 JWT with `sub`, `employeeId`, `role`, `iss`, `iat`, `exp`.
  `GET /.well-known/jwks.json` publishes the public key.
- Consume `workforce.employee.*` on queue `identity.accounts`: create/update/disable the account; a
  tombstone disables it.
- OIDC authorization code + PKCE for Google and GitHub: the provider's verified e-mail must match
  an active account, otherwise the login is refused. The callback redirects with the JWT.
- `PUT /v1/auth/accounts/{employeeId}/password`.

**Acceptance:** local login and one OIDC provider work end to end; a deactivated employee gets 401;
an unknown e-mail is refused; another service validates the issued token through the JWKS cache;
401/403 tests cover missing, expired and forged tokens.

### Slice 3 — Scheduling command (P2.7–P2.9, P2.12)

- PostgreSQL write model: `shift`, `shift_required_job_role`, `shift_assignment`, `shift_approval`,
  `shift_swap`, `shift_swap_approval`, plus outbox, `processed_events` and the employee replica.
- REST writes from `contracts/openapi/scheduling.yaml`. Command endpoints return `202` with the
  resulting resource.
- Rules: no overlapping assignments per employee; only active, qualified employees can be assigned
  (qualification from the replica); approving a swap reassigns in one transaction; a locked shift
  (starts within 24 h) cannot be released for leave.
- Publish `scheduling.shift.*`, `scheduling.assignment.*`, `scheduling.swap.*` and
  `scheduling.shift.understaffed.v1` with pre-filtered candidates.
- `Idempotency-Key` on every POST: stored response for 24 h, `409` when the body differs.

**Acceptance:** unit tests for overlap, qualification and swap approval; IT for the outbox,
idempotent replay and `409`; every published payload validates against its JSON Schema.

### Slice 4 — Scheduling query + GraphQL (P2.10, P2.11)

- MongoDB read model: shifts (with assignments, required roles, suggestions), employees and swaps.
- Projection consumes `scheduling.*`, `workforce.employee.*` and `ai.replacement.suggested.v1` with
  version-aware upserts; tombstones are retained.
- GraphQL endpoint `/graphql` implementing `contracts/graphql/scheduling.graphql`. An Employee sees
  only their own assignments and swaps; Manager/Administrator see everything.
- A rebuild routine replays every event into an empty collection.

**Acceptance:** a GraphQL IT with a real token returns nested shifts and assignments; replaying the
same events in shuffled order ends in the same state; reads still answer with the command role
stopped; the read model is rebuilt from events in the test.

### Slice 5 — Leave command + ledger (P2.13, P2.14)

- PostgreSQL: `leave_type`, `leave_request`, `leave_approval`, append-only `leave_ledger`,
  `balance_snapshot`, `saga`, outbox and the employee replica.
- REST from `contracts/openapi/leave.yaml`. The ledger has no update or delete operation;
  corrections and compensation are new REVERSAL entries.
- Balance check before approval. Approval, in one transaction: `APPROVAL_PENDING` + RESERVATION
  ledger entry + saga row with a deadline + `leave.request.approval-started.v1` in the outbox.
- Consume `scheduling.leave-release.completed|rejected`: on completed mark APPROVED; on rejected or
  timeout append a REVERSAL entry and mark REJECTED with a reason.
- Snapshot the balance every 50 ledger entries.

**Acceptance:** unit tests for balance, reversal and the snapshot boundary; the ledger cannot be
mutated through the API; a saga IT with a stubbed reply covers both outcomes and the timeout.

### Slice 6 — Leave query (P2.15)

- MongoDB overviews and balances projected from `leave.*` + `workforce.employee.*`.
- REST GETs for `/v1/views/employee-leave-overview*`; the gateway sends GETs to this role.
- Rebuildable from events.

**Acceptance:** the overview matches the command database after replay; shuffled order produces the
same balance.

### Slice 7 — Notification (P4.3)

- Consume `notification.events` (scheduling, leave and AI events). Map each event to recipients and
  a message; deduplicate by `eventId`.
- REST: `GET /v1/notifications` for the logged-in user, `PATCH /v1/notifications/{id}` to mark read.
- Publish `notification.email.requested.v1` for the serverless function.

**Acceptance:** each covered event produces exactly one notification per recipient; a redelivered
event produces none; an employee cannot read another employee's notifications (403/404 test).

### Slice 8 — Audit (P4.5)

- Consume every event on queue `audit.events` and append the envelope unchanged with a unique
  constraint on `eventId`.
- REST `GET /v1/auditlogs` (Administrator only) with the filters in `contracts/openapi/audit.yaml`.

**Acceptance:** every event in the catalog is stored; a redelivered event is ignored (IT); an
Employee gets 403; the table has no update or delete path.

### Slice 9 — AI service (P4.1, P4.2)

- Consume `scheduling.shift.understaffed.v1` on queue `ai.suggestions`.
- Build the prompt from the candidates in the event; call the model through
  `infrastructure/llm` (Ollama locally, OpenRouter in the cloud plan).
- Validate that the answer contains only candidates from the input; on timeout or invalid output
  fall back to a rule-based ranking (fewest hours this week).
- Persist the prompt/response for review and publish `ai.replacement.suggested.v1`.

**Acceptance:** unit tests for output validation, ranking fallback and timeout; an IT with a stubbed
model publishes a suggestion containing only input candidates; a malformed model answer triggers the
fallback and still publishes.

### Slice 10 — Email function (P4.4)

- Consume `notification.email.requested.v1` from `email.send`, send over SMTP (Mailpit locally) and
  exit. Runs as a KEDA ScaledJob in Kubernetes.
- Idempotent by `eventId`: a redelivered request is not sent twice.

**Acceptance:** an IT with a stubbed SMTP server sends exactly one message per event; a redelivery
sends nothing.

### Slice 11 — Frontend (P5)

- Add a typed REST client and Apollo Client (`@apollo/client`, `graphql` are not installed yet).
- Login page: local login + OIDC redirect; store the JWT and attach `Authorization` to REST and
  GraphQL; generate one `Idempotency-Key` per user action.
- Manager week view: one GraphQL query for shifts, assignments, employee names and suggestions;
  assigning a suggested employee is a REST POST.
- Employee pages: my shifts (GraphQL), my leave requests (REST), notifications (REST).

**Acceptance:** the schedule page loads from GraphQL and assigning refreshes it; a retried POST does
not create a duplicate; an Employee cannot see another employee's schedule page data.

## 5. Phase 2 — distributed-systems patterns (P3)

These are the patterns the report must demonstrate, so each gets a test that fails if the pattern is
removed.

| Pattern | Task | Proof |
|---|---|---|
| Saga with compensation | P3.1 | `LeaveApprovalSagaIT` (enabled): completed path and locked-shift compensation |
| Idempotent commands | P3.2 | IT: same key returns the first response, different body → 409 |
| Idempotent consumers | P3.2 | IT: redelivering an event does not duplicate state or notifications |
| Tombstones | P3.4 | unit + IT: late update cannot resurrect a deleted entity |
| Snapshot events | P3.5 | schema test: consumers can upsert without calling the producer |
| Balance snapshots | P3.5 | unit test: balance = newest snapshot + later entries |
| Commutative handlers | P3.3 | replay events in shuffled order, assert identical state |
| CQRS justification | P3.6 | measure schedule query latency with and without the read model for the report |
| Caching | P3.7 | JWKS is fetched once; optional short-lived GraphQL cache only if load tests justify it |

Sequencing: implement the saga in the same sprint as the Leave approval slice, not at the end. The
cooperation test is the acceptance test for P3.1.

## 6. Phase 3 — system tests, security and quality (P6)

- Every service: unit tests for its rules (`*Test`) and at least one of each integration category in
  the course description: API behaviour, messaging, database (`*IT`).
- Security matrix per service: no token → 401, expired token → 401, forged signature → 401, wrong
  role → 403, idempotency replay and conflict.
- `ServiceStartupSmokeIT` keeps the cooperation harness honest; `LeaveApprovalSagaIT` is the required
  cooperation test.
- Static analysis: CodeQL and dependency review already run; enable the SonarQube Cloud gate
  (P1.5) when the `SONAR_TOKEN` exists.
- Contract lint must stay green: a broken OpenAPI, AsyncAPI or JSON Schema fails the PR.

## 7. Phase 4 — Kubernetes and observability (P7)

Deferred until at least Workforce + Identity + Scheduling command/query run in Compose, so the
manifests describe something real.

1. Local cluster (kind or minikube) + Helm infrastructure: ingress-nginx, KEDA, RabbitMQ, PostgreSQL,
   MongoDB, Ollama, Prometheus/Loki/Tempo/Grafana.
2. Fill secrets (`identity-service-secrets`, database credentials) and verify
   `kubectl apply -k k8s/overlays/local`.
3. Autoscaling demo: HPA on the two query roles, KEDA ScaledObject on the AI consumer and ScaledJob
   for the email function.
4. OTel traces through REST → RabbitMQ → consumer, with the correlation id visible in Grafana.
5. CD: images are already pushed to GHCR on `main`; add the deploy + smoke-test step (P7.5).

## 8. Suggested split for five people

Fill in the names from `docs/project-plan.md` §1; every person owns at least one service and one
cross-cutting area.

| Slot | Owns | First PR | Then |
|---|---|---|---|
| A | workforce-service | Slice 1 CRUD + schema | employee events, seed data, identity replica support |
| B | identity-service + security tests | Slice 2 login + JWKS | OIDC, workforce consumer, 401/403 matrix |
| C | scheduling-service command + query | Slice 3 write model + overlap rule | projections, GraphQL, understaffed event, idempotency |
| D | leave-service + saga | Slice 5 schema + ledger | approval saga, query role, balance snapshots, cooperation test |
| E | ai + notification + audit + email function | Slice 8 audit (simplest) | notification, AI with stub, email function |
| shared | CI/CD, Kubernetes, observability, report | Phase 0 conventions + branch protection | Compose integration, K8s, Grafana, report chapters |

Scheduling is the largest area; pair C with whoever finishes first each sprint. Frontend work is
split by page after Slice 11 starts (schedule/GraphQL, leave/REST, auth/shell).

## 9. CI map

| Workflow | Runs | What it proves |
|---|---|---|
| `ci.yml` | PR, `main`, manual | `changes` → `tests` (matrix: `mvn verify` per changed service) → `images` (matrix: build + Trivy) → `ci` (the required aggregate check); `contracts` and `frontend` jobs appear when those paths change |
| `system-tests.yml` | `main`, nightly, manual | cross-service cooperation tests: images built in parallel, shared as artifacts, then RabbitMQ + two services in containers |
| `codeql.yml` | PR, `main`, weekly | static security analysis (Java compiled with `build-mode: manual`) |
| `nightly-shift-risk.yml` | nightly, manual | scheduled shift-risk report from seed data (artifact, job summary, optional issue) |

See `docs/ci-demo.md` for triggers, the manual commands and troubleshooting.

A red job blocks the merge; do not bypass it. When a service gains its first `*IT`, the integration
job stops printing the "no integration tests yet" warning automatically.

## 10. Definition of done (per slice)

- [ ] Endpoints/events match the contract; no contract file changed without review.
- [ ] Business rules in `domain/`, covered by unit tests that fail if the rule changes.
- [ ] At least one `*IT` for the new persistence/messaging/API path.
- [ ] `mvn -B -f services/<svc>/pom.xml verify` passes locally and in CI.
- [ ] Service README "Done when" boxes updated; `docs/project-plan.md` task ticked.
- [ ] No secrets, no direct database access from another service, no synchronous inter-service call.
- [ ] Logs carry the correlation id; errors are `problem+json`.

## 11. Group session plan (today, ~3 hours)

1. **30 min — land the foundation.** Review and merge this plan, `.clinerules/` and the CI changes.
   One person enables branch protection with the checks from §3 P1.4.
2. **30 min — agree conventions.** Walk through §3 P1.6 and assign one convention per pair. Create
   the GitHub issues from the project-plan IDs.
3. **90 min — first vertical slices in parallel.**
   - Pair A: Workforce employee entity + Flyway `V1__init.sql` + `GET/POST /v1/employees`.
   - Pair B: Identity `account` table + `POST /v1/auth/login` returning a signed JWT (JWKS next).
   - Pair C/D: Scheduling and Leave schemas (`V1__init.sql`) and the overlap/ledger unit rules.
   - Pair E: Audit consumer skeleton + `Notification`/`AI` issue breakdown.
4. **30 min — prove the pipeline.** Open one PR per slice, watch `ci.yml` and
   `system-tests.yml`, fix the first failures together, and update the board.

Do not start Kubernetes or the frontend wiring today; both depend on the services above.


