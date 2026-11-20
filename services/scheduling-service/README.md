# scheduling-service

**Owns:** shifts, required job roles per shift, shift assignments, shift approvals, **shift swaps** and swap approvals.
**Deployed as two CQRS roles from one codebase/image** (`APP_ROLE`):

| Role | Compose | Store | API |
|---|---|---|---|
| `command` | `scheduling-command` | `scheduling-write-db` (Postgres, Flyway) + outbox + employee replica | REST writes: [`openapi/scheduling.yaml`](../../contracts/openapi/scheduling.yaml) |
| `query` | `scheduling-query` | `scheduling-read-db` (MongoDB) + projection log | GraphQL reads: [`graphql/scheduling.graphql`](../../contracts/graphql/scheduling.graphql) |

**Why CQRS here:** writes are small, validated transactions (assign, swap, approve). Reads are wide, nested views such as a week's schedule with assignments, employee names and AI suggestions. Mongo documents shaped for those views, served over GraphQL, avoid multi-table joins on every page load. The two roles also scale independently, since reads far outnumber writes. Measured (P3.6, [`docs/measurements/cqrs-read-model.md`](../../docs/measurements/cqrs-read-model.md)): at one year of data the joins are **not** slower than the read model (median 2.20 ms against 5.05 ms for the deployed batched resolvers; 66.83 ms before the resolvers were batched), so the justification is availability and isolation of the read side, not query latency. `CqrsReadModelBenchmarkIT` repeats the measurement on request only: `-Dcqrs.benchmark=true`, or the `benchmark` input of a manual `ci` run (about 2 min, no latency assertions).

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
| `scheduling.command` | command | `workforce.employee.*.v1` | Upsert the local employee replica (version-based, tombstones win), including `weeklyContractHours` |
| | | `leave.request.approval-started.v1` | **Saga step.** Release the overlapping assignments and reply `completed`. If any overlapping shift is `LOCKED`, starts within 24 h or was checked into, reply `rejected` and change nothing |
| | | `leave.request.approved.v1`, `leave.request.cancelled.v1` | Keep a copy of the leave windows, so the understaffed candidates leave out employees who are away |
| `scheduling.projection` | query | `scheduling.#` | Upsert the shift, assignment and swap documents (highest `aggregateVersion` wins, tombstones retained) |
| | | `workforce.employee.#` | Upsert the employee copy that names and job roles are resolved from |
| | | `ai.replacement.suggested.v1` | Upsert the latest ranking per shift and job role |

`#` and not `*` for the two event families: a topic wildcard matches exactly one word, so `scheduling.*` would only
match `scheduling.shift`.

## Correlation id (P1.6)

`config/CorrelationIdFilter` takes `X-Correlation-Id` from the request (a token of up to 128
`[A-Za-z0-9._:-]` characters) or generates a UUID, puts it in the MDC as `correlationId`, and echoes
it on the response. Every log line prints it (`logging.pattern.correlation`). Published events carry the request's id; events written while consuming carry the consumed event's id. Both listeners (command and query role) put the consumed `correlationId` in the MDC.

## Extract from monolith
`shift`, `shiftrequiredjobrole`, `shiftassignment`, `shiftapproval`, `shiftswap`, `shiftswapapproval` and `view/employeeshiftoverview` (now GraphQL).

## API docs (Swagger UI)

Swagger UI is at `http://localhost:8080/swagger-ui.html` (service port; through the gateway it is not routed). It renders
the hand-written contract `contracts/openapi/scheduling.yaml`, not a spec generated from the controllers. Both the command and the query role serve it, but
neither security chain permits the Swagger paths, so without a Bearer token they answer `401` (no test opens the page). Maven copies
the file onto the classpath at build time (`/openapi/scheduling.yaml`); never edit the copy. The Docker build gets the file
through `--build-context contracts=contracts` (set in Compose and CI).

## Done when
- [ ] The schedule page reads via GraphQL, and create, assign and swap work via REST. (Backend done and tested: `ShiftCommandIT`, `AssignmentCommandIT`, `SwapCommandIT`, `SchedulingGraphQlIT`. Since #104 the frontend reads the schedule pages via GraphQL and writes through `/api/v1`, but it has no swap UI, no frontend test, and has not been run through the gateway.)
- [ ] An approved swap moves the assignment, and both events reach notification and audit. (The move is tested in `SwapCommandIT.approvingASwapMovesTheAssignmentInTheSameTransaction`; notification and audit consume nothing yet: P4.3, P4.5.)
- [x] Cooperation test: `leave.request.approval-started` → `leave-release.completed` or `rejected`: `tests/cooperation/.../LeaveApprovalSagaIT.approvedLeaveReleasesOverlappingShiftsAndCompensatesWhenAShiftIsLocked` (both replies, real leave and scheduling images); in the service `LeaveReleaseIT`.

## Status (2026-11-19)

The command role implements every contract operation it can implement today; the query role serves every read of the
GraphQL contract from its Mongo read model.

| Area | Done |
|---|---|
| Shifts | `POST`/`PUT`/`DELETE /v1/shifts`; the status only moves forward (`PLANNED → PUBLISHED → LOCKED`, `PLANNED`/`PUBLISHED`/`LOCKED` → `CANCELLED`; `PLANNED → LOCKED` is refused); delete is soft and publishes a tombstone |
| Required job roles | `POST`/`PUT`/`DELETE /v1/shiftrequiredjobroles`; the rows are part of the shift aggregate, so every change bumps its version and republishes `scheduling.shift.updated.v1` |
| Employee replica | consumes `workforce.employee.*`: idempotent (`processed_events`), highest `aggregateVersion` wins, a tombstone is retained and never cleared (also when it arrives before the create), and `weeklyContractHours` is stored for the candidate list. Commutative (P3.3): `snapshot_version` (V8) is the version of the snapshot the fields come from, so the same events give the same row in any order (`EmployeeReplicaShuffledOrderTest`) |
| Assignments | `POST`/`PUT`/`DELETE /v1/shiftassignments`; the employee must be active and qualified **in the replica** and must not already work an overlapping shift (half-open `[start, end)`) |
| Approvals | `POST`/`PUT`/`DELETE /v1/shiftapprovals`; one decision per assignment, and `APPROVED` moves the assignment in the same transaction |
| Swaps | `POST`/`PUT`/`DELETE /v1/shiftswaps` and `POST`/`PUT`/`DELETE /v1/shiftswapapprovals`; an approval hands the assignment over (`employeeId` changes, status `SWAPPED`) and publishes `assignment.updated.v1` + `swap.approved.v1` from one commit. A correction (PUT) may change the approver and the comment and republishes the same event type with the next `aggregateVersion`; flipping the decision is a `409`, because the catalog has no reversal event. DELETE removes the record of the decision, not the handover, so no event is published |
| Idempotency | every `POST` stores its response for 24 h: a replay returns it, a different body on the same key is `409` |
| Leave saga | consumes `leave.request.approval-started.v1`, releases the overlapping assignments, and replies `completed` or - if any of those shifts starts within 24 h, is `LOCKED` or was checked into - `rejected` with nothing changed. The answer is stored per leave request, so a redelivered request republishes the same reply |
| Understaffing | a cancelled, released or deleted assignment checks the remaining staffing per required role and publishes `scheduling.shift.understaffed.v1` in the same transaction, with candidates that are active, qualified, free elsewhere and **not on approved leave**, plus `hoursThisWeek` and `weeklyContractHours`. The hours and the leave windows are local copies (replica + `leave.request.approved/.cancelled`), so no synchronous call is made |
| Query role (P2.10) | `scheduling.projection` is bound to `scheduling.#`, `workforce.employee.#` and `ai.replacement.suggested.v1`; shifts, assignments, employees, swaps and AI suggestions are separate Mongo documents with version-guarded upserts; a tombstone is retained and never cleared; every received event is logged so the read model can be rebuilt (`scheduling.#` also delivers `shift.understaffed` and `leave-release.*`, which are logged but change nothing) |
| GraphQL (P2.11) | `/graphql` serves the contract's SDL (copied to `graphql/scheduling.graphqls`, guarded by a drift test) from the read model, with nested shift -> assignments -> employee and shift -> replacementSuggestions -> candidates -> employee in one request |
| Visibility | an Employee sees only their own shifts, schedule and swaps (a `FORBIDDEN` GraphQL error for somebody else's schedule); Manager and Administrator see everything |
| JWT (both roles, P1.7) | `spring-boot-starter-oauth2-resource-server`, signature checked against `JWT_JWKS_URL` (fetched on the first token, then cached by Nimbus) and `iss` validated; `role` becomes `ROLE_<role>`. Query role: every request needs a valid token. Command role: writes need `Administrator` or `Manager`; an `Employee` may only `POST /v1/shiftswaps` with `employeeFromId` = the token's `employeeId`; `401`/`403` are problem+json. `CommandSecurityRulesTest` (no containers) covers missing, forged, expired and foreign tokens and the command role rules, `ViewerTest` the Employee swap-request rule, and `SchedulingGraphQlIT` a missing and a foreign token on the query role (no expired-token test there) |
| GraphiQL (P8.3) | served at `/graphiql` only when the `dev` profile is active (`APP_ROLE=query,dev`) |

- Events are built to `contracts/events/payloads/{shift,assignment,swap,tombstone,leave-release,understaffed}.schema.json` (no test validates them against the schemas); saga replies also set `causationId` to the event that triggered them.
- Every write commits its event through the outbox in the same transaction; the relay publishes with routing key = `eventType`.
- Migrations: `V1__init.sql`, `V2__employee_replica.sql`, `V3__assignments.sql`, `V4__idempotency.sql`, `V5__swaps.sql`, `V6__leave_release.sql`, `V7__understaffing_inputs.sql`, `V8__employee_replica_snapshot_version.sql`.
- The queue has **one** listener that dispatches on `eventType`: two listeners on one queue would be competing consumers.
- Tests: 86 unit (13 classes) and 69 integration (11 classes) (`*IT` with real PostgreSQL, MongoDB, RabbitMQ and a JWKS endpoint; the command tests share one container pair and the query tests another).

**Producers.** Workforce now publishes `weeklyContractHours` and Leave publishes `leave.request.approved.v1`/`.cancelled.v1`; the scheduling integration tests still publish those envelopes built from the schemas, and the cooperation `LeaveApprovalSagaIT` uses Leave's real `approved.v1`.
- Each queue has a `<queue>.dlq` bound to `shift-happens.dlx` with `#`, so both also receive other queues' dead letters. Listeners make three attempts, then republish to the DLX.
- `DELETE /v1/shiftswaps/{id}` (withdraws a `PENDING` swap) and `DELETE /v1/shiftapprovals/{id}` are hard deletes and publish nothing. All writes answer `202 Accepted`; the command role has no GET endpoint besides `/health`.
- Read by the code, not set in Compose (defaults apply): `OUTBOX_RELAY_DELAY` (2000 ms), `MONGO_URI`, `GRAPHQL_GRAPHIQL_ENABLED`, `PROJECTION_REBUILD_ON_START`. Set in Compose but not read: `OUTBOX_ENABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`.

## Not implemented, and why

| Gap | Reason |
|---|---|
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
newer sibling. GraphQL resolves the nesting with indexed lookups, one batched read per nested field (see [GraphQL](#graphql)).

- Every write is a version-guarded upsert: an equal or lower `aggregateVersion` is ignored, and a snapshot never
  clears an existing tombstone.
- The projection is commutative (P3.3): shift, assignment, employee and swap documents carry `snapshotVersion` (the
  version of the snapshot their fields come from) next to `aggregateVersion` (the highest version seen). A snapshot
  that was published before a delete but arrives after it still refreshes the fields, and a tombstone that arrives
  before the create is stored as a document without fields (`snapshotVersion` 0, never returned as a view), so the
  late create cannot resurrect the entity. `SchedulingProjectionShuffledOrderIT` applies one event set in seeded
  shuffles and with redeliveries and compares the stored documents. It assumes what the producers guarantee: a
  tombstone is the last event of its entity. Two suggestions of one shift and role with the same `suggestedAt` have
  no tie-break (the first to arrive stays).
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
- Nested fields are batched, never read per parent: every nested field queues its key on a per-request DataLoader,
  and the loader asks `SchedulingQueryService` once for all parents (`ReadModelQueries.find...ByIds` / `...OfShifts`,
  one `$in` read each). A week of shifts with staffing, assignments and employee names is five MongoDB find commands
  for a Manager and six for an Employee, however many shifts it returns (a result of more than 101 documents adds
  a getMore on its cursor; the benchmarked week of 22 shifts and 102 assignments is 7 round trips, down from 257). `GraphQlBatchingTest` (unit, counting port) and
  `GraphQlRoundTripsIT` (MongoDB's own operation counters) fail if a per-parent read comes back.
- The visibility rules are applied by the root queries only. A nested field has no rule of its own and is resolved
  only for a parent that a root query returned to that viewer; the loaders are created per request, so nothing one
  request loads is served to another.
- GraphiQL is a development tool: `APP_ROLE=query,dev` (or `GRAPHQL_GRAPHIQL_ENABLED=true`) serves it at `/graphiql`.

## Run it

```bash
mvn -B -f services/scheduling-service/pom.xml test     # unit tests, no Docker
mvn -B -f services/scheduling-service/pom.xml verify   # unit + integration (Docker must be running)

# the same plus the CQRS benchmark (P3.6), which is off by default; it prints lines starting with "CQRS-BENCH |"
mvn -B -f services/scheduling-service/pom.xml verify -Dcqrs.benchmark=true
# in CI: gh workflow run ci.yml --ref <branch> -f services=scheduling-service -f benchmark=true

# query role locally, with GraphiQL (the env var wins over APP_ROLE in application.yml)
SPRING_PROFILES_ACTIVE=query,dev mvn -B -f services/scheduling-service/pom.xml spring-boot:run

# rebuild the read model from its own event log (Compose does not pass PROJECTION_REBUILD_ON_START
# to scheduling-query, so in Compose add it to that service's environment instead)
PROJECTION_REBUILD_ON_START=true SPRING_PROFILES_ACTIVE=query mvn -B -f services/scheduling-service/pom.xml spring-boot:run
```
