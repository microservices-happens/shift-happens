# MA2 presentation script — Shift Happens

**Format:** 12 slides, about 12–13 minutes, then discussion and feedback.
**How to read this:** `[SLIDE]` is what the audience sees (keep it short, 3–5 bullets). *Say* is the
spoken text — read it once out loud before you present, it is written to be spoken. ⏱ is the time budget.

**Before you start:** put the architecture diagram from the report on slide 3, the event storm on slide 2
(small), and the CI screenshots on slide 9. The report file `docs/DLS Report-2026-11-20.md` is 2 MB because
of embedded images — copy the pictures out of it rather than screenshotting the PDF twice.

---

## Slide 1 — Title ⏱ 0:30

**[SLIDE]**
- Shift Happens — Development of Large Systems, Mandatory Assignment 2
- The five of us: Luke3520 · jarrald · Jafarr1 · drummdurum · ViggoBeck
- `github.com/microservices-happens/shift-happens`
- Today: what we built, how we split it, what works right now

*Say:* "We are Shift Happens. For MA2 we took the architecture from MA1 and built the system: seven
backend microservices, a React frontend, RabbitMQ between the services and a Docker Compose environment
that runs the whole thing with one command. I will spend most of the time on the system, then we demo it
live, and we finish with the deployment plan and what is still open."

---

## Slide 2 — From a monolith to seven services ⏱ 1:00

**[SLIDE]**
- Problem: monolith at Acme, growing load, vertical scaling exhausted
- Goal: stateless, independently deployable services; scale reads and writes separately
- Seven services: Identity · Workforce · Scheduling · Leave · AI · Notification · Audit
- Two of them are one codebase deployed twice: **Scheduling** and **Leave** (command + query)
- Frontend: REST for commands, GraphQL for the schedule views

*Say:* "The starting point is in the report: the system is in production-like testing at Acme, response
times and availability are suffering, and adding CPU to one server is no longer an option. So we cut the
domain into seven services by business capability — who logs in, who maintains staff, who plans the week,
who takes time off, who covers a gap, who informs people, and who keeps the audit trail. Two of those,
Scheduling and Leave, are split into a write side and a read side. That is nine deployments from seven
codebases, and the reason for the split is the next few slides."

*Transition:* "First, how the pieces talk to each other."

---

## Slide 3 — Architecture on one page ⏱ 1:20

**[SLIDE]** — the architecture diagram from the report, plus three bullets:
- One entry point: Caddy locally, Ingress in Kubernetes. TLS and routing only — **no business logic**
- Service → service: **RabbitMQ events only**. No synchronous calls anywhere
- Each service owns its database; Identity signs the JWT, every service verifies it itself

*Say:* "The browser only ever talks to the gateway. From there, REST `/api/v1/...` goes to the service
that owns the data, and the schedule view goes to the Scheduling query role over GraphQL at `/api/graphql`.
The important design decision is between the services: they do not call each other. Everything is an event
on one topic exchange, and RabbitMQ routes by event type. That is why a service can be down without taking
its neighbours with it, and it is why there is no chain of timeouts to debug at three in the morning.
If Scheduling needs to know that an employee is qualified, it does not ask Workforce — it keeps a local
copy, built from the employee events. The price is that those copies are eventually consistent, normally
less than a second behind."

*Transition:* "The thing that made seven people-in-parallel possible was not the broker, it was the
contracts."


---

## Slide 4 — Contracts first, then parallel work ⏱ 1:20

**[SLIDE]** — two columns: "Contracts" and "Ownership"
- `contracts/`: OpenAPI per service · one GraphQL SDL · AsyncAPI + a JSON Schema per event
- Every event has the same envelope: `eventId`, `eventType`, `aggregateId`, `aggregateVersion`, `data`
- CI validates all four contract types; a contract change needs the owners of every affected service
- Owners: Identity + security tests **ViggoBeck** · Scheduling (command + GraphQL) **jarrald** ·
  Leave + saga **Luke3520** · AI + Ollama **drummdurum** · Notification, Audit, email-function **Jafarr1**
- One backup owner per service; each service has its own README, Dockerfile and seed data

*Say:* "We agreed the contracts before we wrote services, and the repository *is* the contract: OpenAPI for
the REST APIs, one GraphQL schema for the schedule reads, and AsyncAPI plus a JSON Schema per event. Every
event uses the same envelope, so a consumer always knows what it is looking at. CI parses all of it on every
pull request, and a change under `contracts/` requires the owners of every affected service to approve —
that is the CODEOWNERS file. On the right is how we split the ownership, straight from our project plan:
one owner and one backup per service, so every service has someone accountable for reviews and docs. That is
also how we avoided the classic problem where four people block on each other: with the event schema fixed,
Scheduling could be built against test events before Workforce existed."

*Tip:* the plan still has empty cells for workforce, frontend, CI/CD and report coordination — fill them in
before the talk so the slide matches the plan you show. See "Ownership" at the end of this file.

*Transition:* "The clearest example of the contracts paying off is the split of Scheduling."

---

## Slide 5 — CQRS: one service, two deployments ⏱ 1:10

**[SLIDE]**
- **Command**: Postgres write model + transactional outbox. REST writes, answers `202 Accepted`
- **Query**: MongoDB documents shaped for the UI. GraphQL reads. No DataSource at all
- The projection consumes `scheduling.*`, `workforce.employee.*`, `ai.replacement.suggested.v1`
- Reads keep working when the command role is down — separate deployment, separate database
- Same idea in Leave: balances and overviews from Mongo, everything else on the command role

*Say:* "Scheduling is one codebase deployed twice. The command role owns Postgres: it validates the write,
commits the state change and the event in the same transaction through the outbox, and answers 202. The query
role owns MongoDB, where the documents are shaped exactly like the screen: a shift with its assignments, the
employees, the required roles and the AI suggestions — one GraphQL query, no joins. It has no Postgres
connection at all, not even the driver: start the query role without the write database and it starts. That
is the property we actually test — the read side keeps answering while the command side is stopped. We did
not apply CQRS everywhere on purpose: Identity and Workforce are plain CRUD, and Audit only appends, so the
extra moving parts would buy nothing."

*If asked why it is justified:* reads dominate the load, the views are deeply nested, and the two sides scale
independently. Report section 2.4.1 has the full argument.

*Transition:* "Two workflows show what the events are for."

---

## Slide 6 — Two asynchronous workflows ⏱ 1:30

**[SLIDE]** — left: "Understaffed → AI", right: "Leave approval = saga"
- A shift loses an employee → `scheduling.shift.understaffed.v1` with **pre-filtered** candidates
- AI service ranks them, validates the answer, falls back to a rule; the manager still assigns over REST
- Leave approval: RESERVATION → `approval-started` → Scheduling releases shifts → `completed`
- Refused (locked shift or starts within 24 h) or timeout → **compensation**: REVERSAL + REJECTED

*Say:* "On the left: when a shift loses an employee, Scheduling does not call the AI service. It publishes
an event with the shift and a candidate list it has already filtered — active, qualified, free at that time,
not on approved leave — and the AI service answers with a ranking and one short reason. The model is
advisory: it never changes the schedule, the manager still presses assign. If the model is slow, or returns
something that does not match the input, the service falls back to a rule, so the flow never blocks on the
LLM. On the right: approving leave has to release shifts that another service owns, and no distributed
transaction can do that. So Leave orchestrates a saga — it reserves the days, asks Scheduling to release,
then either confirms or compensates with a REVERSAL ledger entry and a rejection. One service holds the
state, the timeout and the compensation, which is why we chose orchestration over choreography."

*Transition:* "Those workflows are built from a handful of patterns we can point at in the code."

---

## Slide 7 — Patterns we can point at in the code ⏱ 1:00

**[SLIDE]** — one line each; put a class or test name next to it:

| Pattern | Where | What it buys us |
|---|---|---|
| Transactional outbox | every producer | state change and event commit together |
| Idempotency (`Idempotency-Key`) | every REST POST | a retried click cannot double-book |
| Idempotent consumer (`processed_events`) | every consumer | redelivery is harmless |
| Commutative handlers | projections and replicas | shuffled order ends in the same state |
| Tombstone + snapshot | deletes; every event | a late event cannot resurrect a deleted row |
| Immutable ledger, snapshot every 50 | Leave | a balance is auditable and cheap |
| Saga with compensation | Leave ↔ Scheduling | consistency across two databases |
| Serverless job (KEDA ScaledJob) | email-function | bursty work, zero pods when idle |

*Say:* "I will not read the table. The one I want to highlight is the fourth: message order is not
guaranteed, so every handler keeps the highest version it has seen, a tombstone wins over anything older,
and a balance is a sum, which is order-independent. We do not just claim that — there is a test per
projection that applies the same events in shuffled order and asserts the final state is identical. The
second one is what a user notices: every POST takes an `Idempotency-Key`, we keep the first response for
24 hours, so a double click or a browser retry returns the same answer instead of creating a second shift."

*Transition:* "Enough architecture. How do you actually run this?"

---

## Slide 8 — A development setup you can hand to someone ⏱ 0:50

**[SLIDE]**
- Multi-stage Dockerfile per service; the runtime image is a JRE plus a jar, no build tools
- All configuration from environment variables — no host, port or password is hard-coded
- Flyway migration per Postgres service; `scripts/seed-dev.sh` loads 13 employees, contracts, roles
- `GET /health` on every service, used by Compose health checks and the Kubernetes probes
- Compose profiles: infrastructure by default, `app` for the services and the gateway, `ai` for Ollama
- Databases sit on internal networks; only the gateway publishes a port

*Say:* "This was an explicit requirement of the assignment, and it is also what let five people work on
separate machines. Every service has a multi-stage Dockerfile, so the image contains a JRE and a jar and
nothing else. Everything configurable is an environment variable with the same name in Compose and in
Kubernetes. The schema is Flyway, so a new teammate gets the right tables on first start, and
`scripts/seed-dev.sh` gives you thirteen employees with contracts and job roles — which is exactly what the
demo will show. And then one command: `docker compose --profile app up --build` and you have the gateway on
`https://localhost:8443`."

*Tip if someone asks about the seed:* the seed only writes the Workforce database. The other services get
their copies through the employee events — so seeding is also an integration test of the async path.


---

## Slide 9 — Tests and the pipeline that runs them ⏱ 1:20

**[SLIDE]**
- **46** unit test classes and **44** integration test classes; **3** cooperation tests
- Integration tests use real engines through Testcontainers — PostgreSQL, MongoDB, RabbitMQ. No H2
- Security tests: missing / forged / expired token → 401, wrong role → 403, OIDC with unknown email refused,
  replayed `Idempotency-Key` returns the stored response
- Four GitHub Actions workflows: `ci` (matrix over changed services), `system-tests`, `codeql`, nightly report
- `ci`: unit + integration → image build + Trivy → contract lint → frontend typecheck → Sonar gate
- `system-tests`: two or more real services plus RabbitMQ in containers

*Say:* "Testing is where most of the work went. Ninety test classes, and the integration ones are not
mocked: they start a real Postgres, a real Mongo and a real RabbitMQ through Testcontainers, so we test the
migrations and the broker contract, not a stand-in. Three of them are cooperation tests that start several
services and assert what they exchange — the leave saga in both outcomes, employee replication, and
Scheduling to AI with a stubbed model. In the pipeline, every changed service runs its own tests in a matrix,
then its image is built and scanned, the contracts are parsed, the frontend is typechecked, and Sonar and
CodeQL run on the whole repository. The one check we require on main is the aggregate `ci` job, so a red
service blocks the merge."

*Tip:* put the two screenshots from the report here — a red pipeline and a green one. It is the cheapest
slide to believe.

---

## Slide 10 — Live demo ⏱ 2:00

**[SLIDE]** — five beats, nothing else:
1. Log in (real JWT from Identity)
2. Create a shift, assign, swap, approve — over REST, through the gateway
3. Correct a decision (202) and try to reverse it (409)
4. Cancel the assignment → understaffed → AI ranks replacements → read it over GraphQL
5. Same GraphQL query with an employee token → only that employee's data

*Say:* "Now the system. Everything you see goes through the gateway on `https://localhost:8443`; there is no
direct call to a service." — then run the runbook at the end of this file. Narrate what changed in the
database and in the read model while the next command runs; silence during a demo feels like a failure.

*If the demo breaks:* do not debug live. Switch to the screenshots and say "the same flow is asserted by the
integration tests, here is the run" — then move on.

---

## Slide 11 — Deployment: Compose today, Kubernetes next ⏱ 1:00

**[SLIDE]**
- Compose is the development environment you just saw; one command, isolated networks per database
- Kubernetes: plain manifests — one Deployment and Service per service role, one namespace, one Ingress
- Readiness/liveness probes on `/health`; ConfigMap for shared values; per-service Secrets
- HPA on CPU for the query roles and frontend (metrics-server installed)
- KEDA: ScaledJob for the email function (0 → 5 jobs on queue length); ScaledObject for a queue consumer
- Cloud plan (no deployment required): AKS + Flexible Server + Atlas + Key Vault + Managed Grafana

*Say:* "The development environment is the one you saw. For production-like we have plain Kubernetes
manifests with one Deployment and Service per service role, probes on the same health endpoint,
configuration in a ConfigMap and the passwords in per-service Secrets that never enter the repository.
Routing is an Ingress with the same path rules as Caddy. Autoscaling uses metrics-server and CPU HPA for
the read side and frontend, because reads dominate; KEDA handles the queue-driven parts — the email
function is a ScaledJob that goes to zero when `email.send` is empty. A 500-message burst started five
Jobs, and the frontend HPA scaled from two to five replicas under load. The cloud plan in the report maps
each local component to an Azure service, with the note that managed databases cost more but remove the
operational work."

*Be precise:* the Docker Desktop cluster runs the manifests, metrics-server, HPAs and KEDA; the CD step and
the Kubernetes observability stack are the next slice. See "Claims to soften".

---

## Slide 12 — What is done, what is next, and what we want feedback on ⏱ 1:00

**[SLIDE]**
- Done and demoed today: seven services, contracts, command/query split, both async workflows, tests, CI
- Next: Notification, Audit and the email function (scaffolded, not implemented), GraphQL in the frontend
  (Apollo), OpenTelemetry dashboards, per-service version tags and the deploy step
- Known trade-off: eventual consistency between the write model and the read model — normally under a second,
  and the read side survives the write side being down
- Question for you: is the CQRS split worth its cost here, and would you have split Notification out?

*Say:* "To be honest about the state: three services are designed and scaffolded but not implemented, and
those are our next slice. The frontend currently reads over REST; wiring the schedule pages to GraphQL is the
step after that. What we would like feedback on is the boundary question: we split Notification out because
the course asks for it, but the reverse question — whether CQRS is worth two databases per service — is the
one we argue about internally. Thank you."

*If time is short:* drop slide 11 to three sentences and go straight to the demo. Never cut the demo.


---

# Ownership (from `docs/project-plan.md` §1)

| Area | Owner | Backup |
|---|---|---|
| identity-service + security tests | `ViggoBeck` | *fill in* |
| workforce-service | *fill in* | *fill in* |
| scheduling-service (command + GraphQL query) | `jarrald` | *fill in* |
| leave-service + saga | `Luke3520` | *fill in* |
| ai-service + Ollama | `drummdurum` | *fill in* |
| notification-service, audit-service, email-function | `Jafarr1` | *fill in* |
| frontend (REST + GraphQL + OIDC login) | *fill in* | *fill in* |
| CI/CD, Kubernetes, observability | *fill in* | *fill in* |
| report + presentation coordination | *fill in* | *fill in* |

Team: `Luke3520`, `jarrald`, `Jafarr1`, `drummdurum`, `ViggoBeck`.

**Who says what (suggested, five speakers):**

| Slides | Speaker | Why that owner |
|---|---|---|
| 1–2, 11 | ViggoBeck | introduction, problem, deployment and security angle |
| 3, 5, 10 | jarrald | architecture, CQRS, drives the demo |
| 6 (saga), 7 | Luke3520 | owns the saga and the compensation |
| 6 (AI), 9 | drummdurum | owns AI + Ollama, knows the test numbers |
| 8, 12 | Jafarr1 | dev setup, scaffolded services, closing |


---

# Demo runbook (PowerShell, verified on a clean stack)

Prerequisite: Git Bash (for the seed script). Everything below was run end to end before writing this file.
The ids (`shift=1`, `assignment=1`, …) depend on state; on a clean stack they start at 1.

```powershell
# 0) reset and start the stack with a known first admin
$pw = '<pick at least 8 characters>'   # local demo only; never committed
docker compose -f docker-compose.microservices.yml --profile app down -v
$env:BOOTSTRAP_ADMIN_EMAIL='admin@shift-happens.example'
$env:BOOTSTRAP_ADMIN_PASSWORD=$pw
$env:SEED_DEMO_PASSWORD=$pw
docker compose -f docker-compose.microservices.yml --profile app up -d --build
# wait until the app containers are healthy, then:
& 'C:\Program Files\Git\bin\bash.exe' scripts/seed-dev.sh
```

```powershell
# 1) log in: a real RS256 token from Identity, through the gateway
$api = 'https://localhost:8443/api'
$tok = (Invoke-RestMethod -Method Post -Uri "$api/v1/auth/login" -SkipCertificateCheck `
        -ContentType 'application/json' `
        -Body (@{ email='admin@shift-happens.example'; password=$pw } | ConvertTo-Json)).token

function Post($path, $body) {
    Invoke-RestMethod -Method Post -Uri "$api$path" -SkipCertificateCheck `
        -Headers @{ 'Idempotency-Key' = [guid]::NewGuid().ToString(); Authorization = "Bearer $tok" } `
        -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 6 -Compress)
}
function Put($path, $body) {
    try { $r = Invoke-WebRequest -Method Put -Uri "$api$path" -SkipCertificateCheck `
            -Headers @{ Authorization = "Bearer $tok" } -ContentType 'application/json' `
            -Body ($body | ConvertTo-Json -Compress); "PUT $($r.StatusCode)" }
    catch { "PUT $($_.Exception.Response.StatusCode.value__) :: $($_.ErrorDetails.Message)" }
}
function Delete($path) {
    try { $r = Invoke-WebRequest -Method Delete -Uri "$api$path" -SkipCertificateCheck `
            -Headers @{ Authorization = "Bearer $tok" }; "DELETE $($r.StatusCode)" }
    catch { "DELETE $($_.Exception.Response.StatusCode.value__)" }
}

# 2) shift -> assignment (Ida, 4) -> swap (Ida to Emma, 5) -> approve
$s  = Post '/v1/shifts' @{ departmentId=2; workLocationId=2; shiftName='Demo early'
         startDatetime='2027-07-05T06:00:00Z'; endDatetime='2027-07-05T14:00:00Z'
         shiftStatus='PUBLISHED'; requiredJobRoles=@(@{ jobRoleId=1; requiredEmployeeCount=1 }) }
$a  = Post '/v1/shiftassignments' @{ shiftId=$s.shiftId; employeeId=4; assignmentStatus='ASSIGNED' }
$w  = Post '/v1/shiftswaps' @{ originalShiftAssignmentId=$a.shiftAssignmentId
         employeeFromId=4; employeeToId=5; reason='demo' }
$ap = Post '/v1/shiftswapapprovals' @{ shiftSwapId=$w.shiftSwapId; approverEmployeeId=2
         decision='APPROVED'; shiftSwapComment='ok' }

# 3) the honest-API beat: correct (202), try to reverse (409), delete (202), delete again (404)
Put    "/v1/shiftswapapprovals/$($ap.shiftSwapApprovalId)" @{ shiftSwapId=$w.shiftSwapId; approverEmployeeId=3; decision='APPROVED'; shiftSwapComment='corrected by Mads' }
Put    "/v1/shiftswapapprovals/$($ap.shiftSwapApprovalId)" @{ shiftSwapId=$w.shiftSwapId; approverEmployeeId=3; decision='REJECTED'; shiftSwapComment='changed my mind' }
Delete "/v1/shiftswapapprovals/$($ap.shiftSwapApprovalId)"
Delete "/v1/shiftswapapprovals/$($ap.shiftSwapApprovalId)"
```

Expected: `PUT 202`, `PUT 409 :: … a decision cannot be reversed because the catalog defines no reversal
event`, `DELETE 202`, `DELETE 404`.


```powershell
# 4) cancel the assignment -> understaffed event -> AI ranks replacements -> read it over GraphQL
Delete "/v1/shiftassignments/$($a.shiftAssignmentId)"
Start-Sleep 12
$q = '{ shifts(from:"2027-07-05T00:00:00Z", to:"2027-07-06T00:00:00Z") { shiftName isUnderstaffed
        requiredJobRoles { jobRoleId assignedCount }
        replacementSuggestions { source reason candidates { rank employee { firstName lastName } } } } }'
(Invoke-RestMethod -Method Post -Uri "$api/graphql" -SkipCertificateCheck `
    -Headers @{ Authorization = "Bearer $tok" } -ContentType 'application/json' `
    -Body (@{ query = $q } | ConvertTo-Json)).data.shifts | ConvertTo-Json -Depth 8
```

Expected: `isUnderstaffed: true`, `assignedCount: 0`, and a suggestion with `source: "RULE"` plus four ranked
names (Sofie, Ida, Emma, Oliver) — because Ollama is not running.

```powershell
# 5) the visibility beat: the new holder sees the shift, the old holder does not
$emma = (Invoke-RestMethod -Method Post -Uri "$api/v1/auth/login" -SkipCertificateCheck `
         -ContentType 'application/json' `
         -Body (@{ email='emma.pedersen@shift-happens.example'; password=$pw } | ConvertTo-Json)).token
$ida  = (Invoke-RestMethod -Method Post -Uri "$api/v1/auth/login" -SkipCertificateCheck `
         -ContentType 'application/json' `
         -Body (@{ email='ida.hansen@shift-happens.example'; password=$pw } | ConvertTo-Json)).token
$q2 = '{ shifts(from:"2027-07-05T00:00:00Z", to:"2027-07-06T00:00:00Z") { shiftId } swapRequests { shiftSwapId } }'
foreach ($t in @($emma, $ida)) {
    (Invoke-RestMethod -Method Post -Uri "$api/graphql" -SkipCertificateCheck `
        -Headers @{ Authorization = "Bearer $t" } -ContentType 'application/json' `
        -Body (@{ query = $q2 } | ConvertTo-Json)).data | ConvertTo-Json -Compress
}
```

Expected: Emma gets the shift; Ida gets `{"shifts":[],"swapRequests":[{"shiftSwapId":1}]}` — she no longer
holds the shift, but she still sees the swap she is part of. That one line is the whole role model.

**The async beat that needs no typing:** the seed is the async story. Workforce writes only its own database
and outbox; Identity creates the accounts and Scheduling fills its replica:

```powershell
docker compose -f docker-compose.microservices.yml exec -T scheduling-write-db psql -U scheduling -d scheduling -c "select count(*) from employee_replica;"
```

---

# Claims to soften (the report is ahead of the code in eight places)

Check these before the talk; a wrong claim in the Q&A costs more than an honest gap.

| The report says | In the repository today | Say instead |
|---|---|---|
| Frontend uses Apollo Client and the schedule pages read over GraphQL | no `apollo`/`graphql` dependency; pages call REST in `frontend/src/api/` | "the GraphQL API is live and demoed; moving the pages is the next frontend slice" |
| Notification, Audit, email-function are described in §2.2 | one class each (application + health); `email-function` exits, so Compose restarts it | "designed, scaffolded with contracts; implementation is next" |
| "Every service is instrumented with OpenTelemetry" (§3.2.3) | collector is in the stack, no OTel SDK dependency in any service yet | "the collector is wired into Compose; SDK instrumentation and dashboards are next" |
| "Every POST carries an Idempotency-Key" (frontend, §2.3) | the API **requires** it; the frontend does not send it yet | "the API enforces it; the frontend wiring is a small next step" |
| "Deploy to the local cluster with a smoke test" (§3.2.2) | images are built and pushed to GHCR on `main`; no deploy workflow | "build and push are green; the deploy job is the remaining CD step" |
| "KEDA ScaledObjects on queue consumers" (§3.2.4) | only the email-function `ScaledJob` manifest | "the ScaledJob exists; a queue ScaledObject is next" |
| "Every service validates the JWT" | 6 of 7 — `leave-service` has no security config yet | "six of seven; leave gets it with its next slice" |
| Semver tags per service (§5.3) | scheme defined, no tag workflow | "the versioning scheme is agreed; tagging is next" |

**Do not show `docker ps` unfiltered:** `email-function` is a console app that exits, so Compose shows it as
"Restarting". If you demo containers, filter to the services (`docker compose ps scheduling-command
scheduling-query workforce-service identity-service`) or explain it in one sentence.

**Ollama:** the demo works without it (the suggestion falls back to `source: "RULE"`). If you want the LLM
beat, start `--profile ai` and pull the model well before the talk — the first pull takes minutes.


---

# Questions we should expect

1. **Why no synchronous calls between services?** Availability: a Workforce outage must not stop scheduling.
   Each consumer keeps a local copy built from snapshot events; the cost is that replicas are eventually
   consistent, normally under a second. It also removes chains of timeouts.
2. **Why CQRS only in Scheduling and Leave?** The read and write shapes are wildly different there (nested
   schedule views vs. transactional rules). In Identity and Workforce it is plain CRUD; Audit only appends,
   so a second model would add cost without a benefit. The report section 2.4.1 has the argument.
3. **How do you not lose or double an event?** The state change and the event commit in one transaction
   (outbox), the relay publishes afterwards, and every consumer stores `eventId`s in `processed_events` in
   the same transaction as its own change. So: at-least-once delivery, effectively-once processing.
4. **What if the read model is behind or wrong?** It is rebuilt from its own projection log, oldest first,
   and a test asserts the rebuilt state is identical. Reads stay up even when the command role is down,
   because the query role has its own database and no Postgres connection.
5. **Why RabbitMQ and not Kafka?** We need routing by event type and one durable queue per consumer, not a
   partitioned log with offset management. Topic exchange, DLX after three attempts, and per-consumer scaling
   cover the course requirements with far less operational surface.
6. **What if the LLM is down or slow?** Timeout, then the rule fallback ranks by fewest scheduled hours; the
   event is always published. The output is validated first: only candidate ids from the input, each exactly
   once, ranks 1..n, at most 100 words. The AI never writes the schedule.
7. **How does the saga compensate, and what about the timeout?** A REVERSAL ledger entry plus REJECTED with a
   reason; a scheduled job times out stuck sagas; the reply is stored per leave request, so a redelivery
   republishes the same answer instead of a second compensation.
8. **Is the shared `contracts/` folder not coupling?** It is a contract, not code: no shared library, no
   shared parent POM, every service builds and deploys alone. A change there is reviewed by every affected
   owner — that is the point.

---

# Pre-flight checklist

**The day before**
- [ ] Fill the four empty owner cells in `docs/project-plan.md` §1 (workforce, frontend, CI/CD, report) and
      mirror them on slide 4.
- [ ] Export the deck to PDF as a backup; record a 60-second screen capture of the demo as a second backup.
- [ ] Screenshots ready: green `ci` run, one red-then-green pipeline, CodeQL/Sonar tabs, RabbitMQ queues
      (`scheduling.command`, `scheduling.projection`, their `.dlq`), a Mongo document, and a GraphQL result.

**20 minutes before**
- [ ] `docker compose … down -v` then `up -d --build` (the rebuild matters: images are stale after a pull).
- [ ] `bash scripts/seed-dev.sh` and log in once, so the admin password is set.
- [ ] Run the demo once completely, then reset again so the ids start at 1 while you present.
- [ ] Close the sensitive tabs; delete the scratch files (`.demo-token`, `docker-up.log`) so they are never
      committed.

**During**
- [ ] One person drives the keyboard, another speaks. Never debug live — switch to the screenshots.
- [ ] Watch the clock at slide 5, 9 and 10: those are the ones that eat time.
- [ ] End on the question you want feedback on (slide 12), not on a feature list.

**Files to reference in the deck**
- `docs/shift-happens-high-level-architecture.drawio` — architecture diagram
- `docs/DLS Report-2026-11-20.md` — event storm, historical model, architecture, pipeline screenshots
- `contracts/` — OpenAPI, GraphQL SDL, AsyncAPI, event schemas
- `docs/ci-demo.md` — one-page summary of the four workflows (good handout)

