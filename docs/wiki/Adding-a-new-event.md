# Adding a new event

An ordered checklist for adding (or versioning) an event between services. Every step points at the
real example that is already on `main`: **`workforce.employee.created.v1`**, published by
workforce-service through its outbox and consumed by identity-service (queue `identity.accounts`) and
scheduling-command (queue `scheduling.command`), among others.

The rules come from
[`contracts/README.md`](https://github.com/microservices-happens/shift-happens/blob/main/contracts/README.md#events)
and the "Messaging" and "Git" sections of
[`CLAUDE.md`](https://github.com/microservices-happens/shift-happens/blob/main/CLAUDE.md). Paths below are
relative to the repo root; `<pkg>` is `src/main/java/dk/ek/shift_happens/<service>`.

**Everything on this page lands in one pull request**: payload schema, `asyncapi.yaml`, `catalog.md`,
producer, consumer(s), their tests and the service READMEs. Services share no library, so the patterns
are copied per service on purpose; copy from the files named here, do not invent a shared module.

---

## 1. Name it

- `eventType` = `<context>.<entity>.<what-happened>.v<N>`, lower case, e.g. `workforce.employee.created.v1`.
  It must match the pattern in
  [`contracts/events/envelope.schema.json`](https://github.com/microservices-happens/shift-happens/blob/main/contracts/events/envelope.schema.json):
  `^[a-z]+\.[a-z-]+\.[a-z-]+\.v[0-9]+$` (no hyphen in the context; hyphens allowed in entity and event,
  as in `scheduling.leave-release.completed.v1`).
- The **routing key is the `eventType`**, on the topic exchange `shift-happens.events`.
- A delete is a tombstone: `<context>.<entity>.deleted.v1` with
  `contracts/events/payloads/tombstone.schema.json` (`entityType`, `entityId`, `deletedAt`).
- A breaking change to the payload is a new version: publish `.v2` **next to** `.v1` until every consumer has
  moved. Additive changes stay in the same version.
- If the producer is a service that has never published before, add it to the `producer` enum in
  `envelope.schema.json`.

## 2. Payload schema

Add or reuse `contracts/events/payloads/<entity>.schema.json` (JSON Schema 2020-12). Model it on
[`employee.schema.json`](https://github.com/microservices-happens/shift-happens/blob/main/contracts/events/payloads/employee.schema.json):

- `data` is a **full snapshot of the entity after the change**, never a diff, so a consumer can upsert
  without calling back.
- List every field consumers rely on in `required`.
- No passwords, tokens or salary (the employee schema says so in its description, and
  `WorkforceApplicationIT` asserts the body contains neither `salary` nor `password`).

The envelope around it is fixed: `eventId` (UUID), `eventType`, `occurredAt`, `producer`, `aggregateId`,
`aggregateVersion` (starts at 1, increments on every change of the aggregate), `correlationId`,
`causationId` (the triggering `eventId`, or `null`), `data`.

## 3. `contracts/asyncapi.yaml`

Copy the four pieces that exist for `workforce.employee.created.v1`:

| Piece | Example key | What it holds |
|---|---|---|
| Channel | `channels.workforceEmployeeCreatedV1` | `address: <eventType>`, the `amqp` binding to `shift-happens.events` (topic, durable, vhost `shift-happens`) |
| Send operation | `operations.publish_workforceEmployeeCreatedV1` | `action: send`, summary "`<producer>` publishes (via transactional outbox)", tag = producer |
| One receive operation per consuming queue | `operations.identity_accounts__workforceEmployeeCreatedV1` | `action: receive`, summary "`<consumer>` consumes from queue `<queue>`", `amqp.ack: true` |
| Message | `components.messages.workforceEmployeeCreatedV1` | `allOf` of `./events/envelope.schema.json` and an object with `eventType: const <eventType>` and `data: $ref ./events/payloads/<entity>.schema.json` |

CI's `asyncapi-html` step fails the job if the spec or a `$ref` into `contracts/events/` does not resolve,
and uploads the rendered docs as an artifact (`contracts/README.md`).

## 4. `contracts/events/catalog.md`

- **Events** table: one row with `eventType`, producer, when it is published, the `data` schema and the
  consuming queues.
- **Queues** table: add the routing key to the "Binds routing keys" cell of every consuming queue (or add a
  new queue row).
- If it is part of a flow (saga, AI suggestion, swap, email), update that flow section too.

## 5. Producer: transactional outbox

Reference: workforce-service. scheduling-service and leave-service carry their own copies of the same
classes; ai-service has a smaller JDBC version (`AiOutboxRelay` in `<pkg>/infrastructure/messaging/AiMessaging.java`).

1. **Outbox table** (already exists in every producer): `outbox_event` in
   `services/workforce-service/src/main/resources/db/migration/V1__init.sql`
   (`event_id`, `event_type`, `aggregate_id`, `payload`, `occurred_at`, `published_at`, `attempts`, plus a
   partial index on unpublished rows). A new producer adds it in its next Flyway migration.
2. **Port**: `application/EventOutbox.java` (`append(EventEnvelope)`); adapter
   `infrastructure/messaging/JpaEventOutbox.java` serialises the envelope and saves the row **in the
   caller's transaction**.
3. **Use case**: in the `@Transactional` method of the application service, save the state change and call
   `outbox.append(...)`. See `EmployeeService.create(...)` in
   `services/workforce-service/<pkg>/application/EmployeeService.java`:
   - the event type is a constant (`EMPLOYEE_CREATED = "workforce.employee.created.v1"`);
   - `aggregateVersion` is the entity's version after the change (deletes bump it once more);
   - `correlationId` is the request's, or a new UUID when there is none;
   - `data` is the full snapshot (`saved.snapshot(...)`), or a tombstone record for deletes.
   Never publish to RabbitMQ directly from a use case.
4. **Relay**: `infrastructure/messaging/OutboxRelay.java` needs no change for a new event; it already
   sends every unpublished row (50 per run, every `OUTBOX_RELAY_DELAY` ms, default 2000) to
   `shift-happens.events` with routing key = `event_type`, as raw JSON with `messageId` = `eventId`, then
   sets `published_at`. Delivery is **at least once**: a crash between publish and update resends, which is
   why consumers de-duplicate.

## 6. Consumer: durable queue, idempotent, commutative

References: `services/identity-service` (Postgres) and scheduling-service's command role
(`@Profile("command")`), both made commutative in #110.

### 6.1 Queue and bindings

`<pkg>/infrastructure/messaging/RabbitTopology.java` (identity) or `SchedulingCommandTopology.java`
(scheduling) declares, at startup:

- the consumer's **durable queue**, named by `RABBITMQ_QUEUE` (`app.rabbit.queue`, e.g.
  `identity.accounts`), with `x-dead-letter-exchange = shift-happens.dlx`;
- one `Binding` per routing key it handles;
- `<queue>.dlq` bound to `shift-happens.dlx`;
- the listener container factory: `setDefaultRequeueRejected(false)`, `RetryInterceptorBuilder.stateless()
  .maxAttempts(3)` and a `RepublishMessageRecoverer` to the DLX. **Three attempts, then dead-letter, never
  requeue forever.**

Bind the **exact** new routing key, as the contract says and as leave and ai do. (identity and
scheduling-command bind the employee events with the wildcard `workforce.employee.*.v1`; that predates the
rule and would also catch a future employee event nobody handles.)

Where the services differ on dead-lettering (details on the
[Troubleshooting](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Troubleshooting.md#messages-pile-up-in-a-dlq) page):

| Service | Dead-letter routing key | DLQ binding |
|---|---|---|
| leave-service (`LeaveCommandTopology`, `LeaveQueryTopology`) | the queue name; retries back off 1 s, 2 s | exactly the queue name; also sets `x-dead-letter-routing-key` |
| identity, scheduling (`SchedulingCommandTopology`, `readmodel/ProjectionTopology`) | the original routing key (`RepublishMessageRecoverer(..., null)`) | `#` |
| ai-service (`AiMessaging.java`) | the original routing key; no retry advice, so it dead-letters on the first failure | `#` |

For a new consumer, prefer the leave variant: its DLQ holds only its own failures, and
`services/leave-service/src/test/java/dk/ek/shift_happens/leave/EmployeeReplicaIT.java` proves it.

The queue must exist before the producer publishes: a topic exchange drops messages that match no queue.
That is why `scripts/seed-dev.sh` checks the queues before seeding.

### 6.2 One listener per queue

`SchedulingCommandListener` is the only `@RabbitListener` on `scheduling.command` and dispatches on
`eventType` (employee events → `EmployeeReplicaService`, `leave.request.approval-started.v1` →
`LeaveReleaseService`, ...). Two listeners on one queue would be competing consumers and a message could land
on the one that does not handle it. Add a branch to the existing listener; log and ignore unknown types.

### 6.3 Handler: idempotency and version rules

The handler is a `@Transactional` method in `application/`, e.g.
`EmployeeProjectionService.apply(...)` (identity) or `EmployeeReplicaService.apply(...)` (scheduling). In
**one transaction** it:

1. returns early if `eventId` is already in `processed_events`
   (`CREATE TABLE processed_events (event_id UUID PRIMARY KEY, processed_at ...)`, e.g.
   `services/scheduling-service/src/main/resources/db/migration/V2__employee_replica.sql`);
2. handles a `*.deleted.v1` tombstone separately (its `data` is not a snapshot);
3. applies the snapshot only if its version is newer;
4. records the `eventId`.

The merge rules live in the domain, without Spring, so they can be unit-tested
(`identity/domain/Account.java`, `scheduling/domain/EmployeeReplica.java`). Since #110 they keep **two**
versions per row:

| Column | Meaning |
|---|---|
| `aggregate_version` | the highest version seen, snapshot **or** tombstone |
| `snapshot_version` | the version of the snapshot the fields come from (0 while only a tombstone has arrived) |
| `deleted_at` (scheduling) / disabled account (identity) | the retained tombstone; never cleared |

which gives an order-independent result:

- a snapshot replaces the fields only if `version > snapshot_version`; a snapshot never clears a tombstone;
- a tombstone that arrives before the create is **stored** as a row without fields, so the late create
  cannot resurrect the entity;
- a snapshot published before the delete but delivered after it may still refresh the fields, but the row
  stays deleted.

Migrations: `services/identity-service/src/main/resources/db/migration/V4__account_snapshot_version.sql`,
`services/scheduling-service/src/main/resources/db/migration/V8__employee_replica_snapshot_version.sql`.

Where the services differ: leave-command's `employee_replica` still has a single `aggregate_version` plus
`deleted_at`; scheduling-query keeps `processed_events` (and a `projection_events` log used for rebuilds) in
Mongo (`readmodel/MongoProjectionEventStore.java`); ai-service uses a table called `processed_event` and
inserts into it with `ON CONFLICT DO NOTHING`. For a new consumer, copy identity or scheduling-command.

Never let a consumer call the producer back. If it needs more data, the snapshot must carry it.

## 7. Tests

| Test | Where | What it proves for the example |
|---|---|---|
| Producer `*IT` | `services/workforce-service/src/test/java/dk/ek/shift_happens/workforce/WorkforceApplicationIT.java` | REST → Postgres → outbox → RabbitMQ with Testcontainers (`postgres:17-alpine`, `rabbitmq:4-management`): binds a test queue, receives the message, checks the routing key, validates `data` against `contracts/events/payloads/employee.schema.json` (networknt JSON Schema, path `../../contracts/events/payloads`), and waits with Awaitility until the outbox row is published |
| Consumer `*IT` | `services/identity-service/src/test/java/dk/ek/shift_happens/identity/IdentityApplicationIT.java`, `services/scheduling-service/src/test/java/dk/ek/shift_happens/scheduling/EmployeeReplicaIT.java` | publishes envelopes to the real exchange and asserts: create, redelivery has no second effect, an older version does not overwrite, a tombstone is kept, a tombstone that overtakes the create wins |
| Dead-letter `*IT` | `services/leave-service/src/test/java/dk/ek/shift_happens/leave/EmployeeReplicaIT.java` | a poison message ends in `leave.command.dlq` after three attempts, and another queue's dead letter does not |
| Shuffled-order unit test | `identity/application/EmployeeProjectionShuffledOrderTest.java`, `scheduling/application/EmployeeReplicaShuffledOrderTest.java` (also `SchedulingProjectionShuffledOrderIT`, leave's `LeaveProjectionReplayIT`) | the same events in every order end in the same state |
| Cooperation test | `tests/cooperation/src/test/java/dk/ek/shift_happens/cooperation/EmployeeReplicationIT.java` | real workforce and identity images + RabbitMQ: POST an employee to workforce, the account appears in identity's database |

For a new event you need at least: the producer `*IT` (including the schema check), a consumer `*IT` per
consuming service (redelivery, older version, tombstone if it has one, dead-letter on a poison message), and
a unit test of the merge rule. Add or extend a cooperation test when the event connects services that no
cooperation test covers yet; a new image must also go into the `build-images` matrix of
`.github/workflows/system-tests.yml`. The cooperation tests run on `main`, nightly and on demand, not on
pull requests, so run them locally (`tests/cooperation/README.md`).

Practical points from the existing tests:

- Testcontainers only, never H2 or an embedded broker. Under Colima, `source scripts/docker-env.sh` first.
- No `Thread.sleep`; wait with Awaitility (`await().atMost(...)`).
- A test queue must be durable and non-exclusive: RabbitMQ 4 rejects transient non-exclusive queues
  (`new Queue(name, true, false, false)` in `WorkforceApplicationIT`). Drain it in `@BeforeEach`.

Run:

```bash
./mvnw -B -f services/<producer>/pom.xml verify
./mvnw -B -f services/<consumer>/pom.xml verify
./mvnw -B -f tests/cooperation/pom.xml verify      # needs the images built with --build-context contracts=contracts
```

## 8. Before you open the PR

- [ ] Name follows `<context>.<entity>.<event>.vN`; routing key = `eventType`
- [ ] Payload schema in `contracts/events/payloads/`, full snapshot, no secrets or salary
- [ ] `contracts/asyncapi.yaml`: channel, send operation, one receive operation per queue, message
- [ ] `contracts/events/catalog.md`: Events row, Queues bindings, flow section if relevant
- [ ] Producer writes the event with `outbox.append` inside the state change's `@Transactional` method
- [ ] Each consumer: durable queue with DLX, exact binding, `<queue>.dlq`, 3 attempts then dead-letter,
      one listener per queue, `processed_events` in the same transaction, version and tombstone rules
- [ ] Producer `*IT` with schema validation; consumer `*IT`s; merge-rule unit test; cooperation test if new
      services cooperate
- [ ] `services/<svc>/README.md` of the producer and every consumer list the event and queue
- [ ] One PR, reviewed by the owners of every affected service (`contracts/README.md`, "Versioning")
