# scheduling-service

**Owns:** shifts, required job roles per shift, shift assignments, shift approvals, **shift swaps** and swap approvals.
**Deployed as two CQRS roles from one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | API |
|---|---|---|---|
| `command` | `scheduling-command` | `scheduling-write-db` (Postgres, Flyway) + outbox + employee replica | REST writes: [`openapi/scheduling.yaml`](../../contracts/openapi/scheduling.yaml) |
| `query` | `scheduling-query` | `scheduling-read-db` (MongoDB) + projection log | GraphQL reads: [`graphql/scheduling.graphql`](../../contracts/graphql/scheduling.graphql) |

**Why CQRS here:** writes are small, validated transactions (assign, swap, approve). Reads are wide, nested views such as a week's schedule with assignments, employee names and AI suggestions. Mongo documents shaped for those views, served over GraphQL, avoid multi-table joins on every page load. The two roles also scale independently, since reads far outnumber writes.

**Why swaps live here:** a swap only reassigns an existing assignment. Approval and reassignment are one local transaction, so no saga is needed.

## Publishes (outbox)
- Shifts: `scheduling.shift.{created,updated,deleted}.v1`
- Assignments: `scheduling.assignment.{created,updated,deleted}.v1`
- Swaps: `scheduling.swap.{requested,approved,rejected}.v1`
- Understaffing: `scheduling.shift.understaffed.v1` (with pre-filtered candidates for ai-service)
- Saga replies: `scheduling.leave-release.{completed,rejected}.v1`

## Consumes
| Queue | Role | Routing keys | Handling |
|---|---|---|---|
| `scheduling.command` | command | `workforce.employee.*` | Upsert the local employee replica (version-based, tombstones win), including `weeklyContractHours` |
| | | `leave.request.approval-started.v1` | **Saga step.** Release the overlapping assignments and reply `completed`. If any overlapping shift is `LOCKED`, reply `rejected` and change nothing |
| | | `leave.request.approved.v1`, `leave.request.cancelled.v1` | Keep a copy of the leave windows, so the understaffed candidates leave out employees who are away |
| `scheduling.projection` | query | `scheduling.#` | Upsert the shift, assignment and swap documents (highest `aggregateVersion` wins, tombstones retained) |
| | | `workforce.employee.#` | Upsert the employee copy that names and job roles are resolved from |
| | | `ai.replacement.suggested.v1` | Upsert the latest ranking per shift and job role |

`#` and not `*` for the two event families: a topic wildcard matches exactly one word, so `scheduling.*` would only
match `scheduling.shift`.

## Extract from monolith
`shift`, `shiftrequiredjobrole`, `shiftassignment`, `shiftapproval`, `shiftswap`, `shiftswapapproval` and `view/employeeshiftoverview` (now GraphQL).

## API docs (Swagger UI)

Swagger UI is at `http://localhost:8080/swagger-ui.html` (service port; through the gateway it is not routed). It renders
the hand-written contract `contracts/openapi/scheduling.yaml`, not a spec generated from the controllers. Both the command and the query role serve it. Maven copies
the file onto the classpath at build time (`/openapi/scheduling.yaml`); never edit the copy. The Docker build gets the file
through `--build-context contracts=contracts` (set in Compose and CI).

## Done when
- [ ] The schedule page reads via GraphQL, and create, assign and swap work via REST.
- [ ] An approved swap moves the assignment, and both events reach notification and audit.
- [ ] Cooperation test: `leave.request.approval-started` → `leave-release.completed` or `rejected`.

## Status (2026-11-19)

The command role implements every contract operation it can implement today; the query role serves every read of the
GraphQL contract from its Mongo read model.

| Area | Done |
|---|---|
| Shifts | `POST`/`PUT`/`DELETE /v1/shifts`; the status only moves forward (`PLANNED → PUBLISHED → LOCKED`, anything → `CANCELLED`); delete is soft and publishes a tombstone |
| Required job roles | `POST`/`PUT`/`DELETE /v1/shiftrequiredjobroles`; the rows are part of the shift aggregate, so every change bumps its version and republishes `scheduling.shift.updated.v1` |
| Employee replica | consumes `workforce.employee.*`: idempotent (`processed_events`), highest `aggregateVersion` wins, a tombstone is retained and never cleared, and `weeklyContractHours` is stored for the candidate list |
| Assignments | `POST`/`PUT`/`DELETE /v1/shiftassignments`; the employee must be active and qualified **in the replica** and must not already work an overlapping shift (half-open `[start, end)`) |
| Approvals | `POST`/`PUT`/`DELETE /v1/shiftapprovals`; one decision per assignment, and `APPROVED` moves the assignment in the same transaction |
| Swaps | `POST`/`PUT`/`DELETE /v1/shiftswaps` and `POST`/`PUT`/`DELETE /v1/shiftswapapprovals`; an approval hands the assignment over (`employeeId` changes, status `SWAPPED`) and publishes `assignment.updated.v1` + `swap.approved.v1` from one commit. A correction (PUT) may change the approver and the comment and republishes the same event type with the next `aggregateVersion`; flipping the decision is a `409`, because the catalog has no reversal event. DELETE removes the record of the decision, not the handover, so no event is published |
| Idempotency | every `POST` stores its response for 24 h: a replay returns it, a different body on the same key is `409` |
| Leave saga | consumes `leave.request.approval-started.v1`, releases the overlapping assignments, and replies `completed` or - if any of those shifts starts within 24 h, is `LOCKED` or was checked into - `rejected` with nothing changed. The answer is stored per leave request, so a redelivered request republishes the same reply |
| Understaffing | a cancelled, released or deleted assignment checks the remaining staffing per required role and publishes `scheduling.shift.understaffed.v1` in the same transaction, with candidates that are active, qualified, free elsewhere and **not on approved leave**, plus `hoursThisWeek` and `weeklyContractHours`. The hours and the leave windows are local copies (replica + `leave.request.approved/.cancelled`), so no synchronous call is made |
| Query role (P2.10) | `scheduling.projection` is bound to `scheduling.#`, `workforce.employee.#` and `ai.replacement.suggested.v1`; shifts, assignments, employees, swaps and AI suggestions are separate Mongo documents with version-guarded upserts; a tombstone is retained and never cleared; every applied event is logged so the read model can be rebuilt |
| GraphQL (P2.11) | `/graphql` serves the contract's SDL (copied to `graphql/scheduling.graphqls`, guarded by a drift test) from the read model, with nested shift -> assignments -> employee -> suggestions in one request |
| Visibility | an Employee sees only their own shifts, schedule and swaps (a `FORBIDDEN` GraphQL error for somebody else's schedule); Manager and Administrator see everything |
| JWT (query role) | `spring-boot-starter-oauth2-resource-server`, signature checked against `JWT_JWKS_URL` and `iss` validated; `role` becomes `ROLE_<role>`; Security ITs cover `401` for missing and forged tokens |
| GraphiQL (P8.3) | served at `/graphiql` only when the `dev` profile is active (`APP_ROLE=query,dev`) |

- Events match `contracts/events/payloads/{shift,assignment,swap,tombstone,leave-release,understaffed}.schema.json`; saga replies also set `causationId` to the event that triggered them.
- Every write commits its event through the outbox in the same transaction; the relay publishes with routing key = `eventType`.
- Migrations: `V1__init.sql`, `V2__employee_replica.sql`, `V3__assignments.sql`, `V4__idempotency.sql`, `V5__swaps.sql`, `V6__leave_release.sql`, `V7__understaffing_inputs.sql`.
- The queue has **one** listener that dispatches on `eventType`: two listeners on one queue would be competing consumers.
- Tests: 63 unit and 59 integration (`*IT` with real PostgreSQL, MongoDB, RabbitMQ and a JWKS endpoint; the command tests share one container pair and the query tests another).

**Contract is ahead of two producers (deliberate).** Workforce does not publish `weeklyContractHours` yet (P2.6) and Leave does not publish `leave.request.approved.v1`/`.cancelled.v1` yet (P2.14); the contract now requires both, and the integration tests publish those envelopes built from the schemas. Scheduling needs no change when the producers catch up.

## Not implemented, and why

| Gap | Reason |
|---|---|
| JWT + role rules on the **command** role (P1.7) | Needs Identity's JWKS (P2.1). The query role already validates tokens; the command role has an explicit `permitAll` chain until P1.7 replaces it, because its tests would otherwise need a token issuer that does not exist yet |
| Reversal of a published swap decision | The event catalog has no `swap.updated` / `swap.reverted`, so a decision can be corrected but not undone, and DELETE only removes the decision record |

## Read model (query role)

| Collection | Key | Content |
|---|---|---|
| `shifts` | `shiftId` | the shift snapshot plus `requiredJobRoles`, `aggregateVersion`, `deletedAt` |
| `assignments` | `shiftAssignmentId` | status, holder and the copied shift window, so a schedule is one range query |
| `employees` | `employeeId` | first/last name, `jobRoleIds`, `weeklyContractHours`, tombstone |
| `swaps` | `shiftSwapId` | the swap and its `decision` block |
| `suggestions` | `shiftId:jobRoleId` | the latest AI ranking; the newest `suggestedAt` wins |
| `processed_events` | `eventId` | idempotent-consumer markers |
| `projection_events` | `eventId` | the event log that the rebuild replays (RabbitMQ keeps no history) |

Separate collections instead of one document per shift: assignment and employee events arrive in any order and are
versioned per aggregate, so one document per entity keeps every upsert local and a late event can never overwrite a
newer sibling. GraphQL resolves the nesting with indexed lookups.

- Every write is a version-guarded upsert: an equal or lower `aggregateVersion` is ignored, and a snapshot never
  clears an existing tombstone.
- The query role has **no DataSource** (`application-query.yml` excludes JPA and Flyway), which is what lets reads keep
  answering while `scheduling-command` and Postgres are down.
- Rebuild: start the query role with `PROJECTION_REBUILD_ON_START=true`. It empties the read-model collections and
  replays `projection_events` oldest first; the log survives, so a rebuild can be repeated. The IT proves the state
  after a rebuild equals the state before it.

## GraphQL

- `/graphql` implements [`contracts/graphql/scheduling.graphql`](../../contracts/graphql/scheduling.graphql). The SDL is
  copied to `src/main/resources/graphql/scheduling.graphqls`, and a unit test fails when the copy and the contract differ.
- Every request needs a Bearer JWT, verified against `JWT_JWKS_URL` with `iss` checked; Nimbus caches the JWKS.
- `assignedCount` and `isUnderstaffed` are derived per request from the assignments and the employee copy, which is why
  the read model keeps the employee replica next to the assignments.
- GraphiQL is a development tool: `APP_ROLE=query,dev` (or `GRAPHQL_GRAPHIQL_ENABLED=true`) serves it at `/graphiql`.

## Run it

```bash
mvn -B -f services/scheduling-service/pom.xml test     # unit tests, no Docker
mvn -B -f services/scheduling-service/pom.xml verify   # unit + integration (Docker must be running)

# query role locally, with GraphiQL (the env var wins over APP_ROLE in application.yml)
SPRING_PROFILES_ACTIVE=query,dev mvn -B -f services/scheduling-service/pom.xml spring-boot:run

# rebuild the read model from its own event log
PROJECTION_REBUILD_ON_START=true docker compose -f docker-compose.microservices.yml --profile app up -d scheduling-query
```
