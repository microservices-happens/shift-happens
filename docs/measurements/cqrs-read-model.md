# CQRS read model: schedule query latency with and without it (P3.6)

**Question:** is the schedule query faster on the MongoDB read model of `scheduling-query` than it would be
on the normalised PostgreSQL write model of `scheduling-command`?

**Short answer:** no, not at this data volume, also after the nested GraphQL resolvers were batched. With one
year of data the write model answers in a median of 2.20 ms and the read model, read the way the deployed
resolvers now read it, in 5.05 ms (7 round trips). Before batching the same query took 66.83 ms in the same run
(257 round trips; 66.45 and 43.48 ms in the two earlier runs). Batching made the deployed read path about 13
times faster; PostgreSQL with joins is still about twice as fast. Latency does not justify CQRS in scheduling;
what does is listed under [Conclusion](#conclusion).

## Method

The benchmark is an integration test,
[`CqrsReadModelBenchmarkIT`](../../services/scheduling-service/src/test/java/dk/ek/shift_happens/scheduling/CqrsReadModelBenchmarkIT.java).
It is opt-in: it only runs with `-Dcqrs.benchmark=true` (see [Reproduce](#reproduce)), so an ordinary
`mvn verify` and the CI of a pull request skip it. Until PR #127 it ran in every build and added 60 to 90 s.

**Data.** One generator fills both stores with the same organisation: 10 departments of 50 employees
(500 employees), three 8-hour shifts per department per day, three required job roles per shift and five
assignments per shift. About 1 in 15 shifts is cancelled, 1 in 40 shifts and 1 in 25 assignments is
soft-deleted, and 1 in 10 assignments is cancelled, so the filters of both sides do real work. The history
is loaded cumulatively and measured at three sizes:

| history | shifts | required-role rows | assignments |
|---|---|---|---|
| 30 days | 900 | 2,700 | 4,500 |
| 90 days | 2,700 | 8,100 | 13,500 |
| 365 days | 10,950 | 32,850 | 54,750 |

- MongoDB is written through the read model's own port (`ReadModelProjection`), so the documents and
  indexes are exactly those of the query role.
- PostgreSQL is a separate container with the command role's real Flyway schema (`V1` to `V8`) and its
  indexes, filled with JDBC batches and followed by `ANALYZE`.

**The question.** The manager's week view for one department, the same selection as the GraphQL query
`shifts(from, to, departmentId) { shiftId shiftName status isUnderstaffed requiredJobRoles { jobRoleId
requiredEmployeeCount assignedCount } assignments { shiftAssignmentId status employee { employeeId firstName
lastName } } }`. The answer is 22 shifts with 102 assignments at every history size.

**Five ways of answering it.**

| variant | what it does | database round trips |
|---|---|---|
| read model (Mongo), per parent (resolvers before batching) | One `SchedulingQueryService` call per parent, as `SchedulingGraphQlController` made them until PR #127: `shifts`, then per shift `requiredJobRoles` (3 reads), `isUnderstaffed` (3 reads) and `assignments` (1 read), then one employee read per assignment. **No longer deployed**; kept so one run shows before and after. | 257 |
| read model (Mongo), batched resolvers (deployed) | The calls the DataLoaders of the controller make for that query: `shifts`, one staffing batch shared by `requiredJobRoles` and `isUnderstaffed` (assignments with `shiftId $in`, employees with `_id $in`), one batch for the `assignments` field and one for their employees. **This is what the query role does today.** | 7 (5 finds and 2 getMore) |
| read model (Mongo), three reads (not deployed) | The same collections read once each: the shifts, their assignments with `shiftId $in`, the employees with `_id $in`. The floor for these collections; the deployed path reads assignments and employees twice, once for the staffing and once for the fields. | 4 (3 finds and 1 getMore) |
| write model (Postgres), joins | Two SQL statements on plain JDBC: `shift LEFT JOIN shift_required_job_role`, and `shift_assignment JOIN shift JOIN employee_replica`. These are the joins the read model avoids. | 2 |
| deployed query over HTTP (JWT, GraphQL, JSON included) | The real request: `POST /graphql` with a Manager token against the running query role, the response parsed by the test. The only variant that pays for HTTP, JWT validation, GraphQL execution and JSON, so **its time is not comparable with the other four**. It shows what a client waits for, and the test asserts that it makes exactly the round trips of the "batched resolvers" variant, so that variant really stands for the deployed code. | 7 |

**Round trips are counted, not assumed.** For the MongoDB variants the number is the difference in the server's
own counters (`serverStatus.opcounters`, `query` plus `getmore`) around one answer. A getMore is a second round
trip on the same cursor: MongoDB returns at most 101 documents in the first batch, and this answer has 102
assignments. That is why reading the assignments once costs 2 round trips here, and why the variant that was
listed with 3 round trips in the earlier runs (where the number was calculated by hand) is 4. For PostgreSQL
the number is the two statements of the variant.

**Same answer.** Before timing, at every size, the test asserts that all five variants return an equal list
(shift fields, required roles with `assignedCount`, `isUnderstaffed`, assignments with employee names). Besides
the round trips of the HTTP variant this is the only assertion; no latency is asserted, so the benchmark cannot
fail on a slow runner.

**Timing.** Per size: 30 warm-up rounds, then 200 measured rounds. One round runs each variant once, so
drift on the runner hits all of them alike. Each call is timed with `System.nanoTime()` on one thread.
Percentiles are nearest-rank.

**Included:** the database round trips and mapping the rows or documents to plain Java records.

**Not included** (except in the HTTP variant, which includes all of it but JPA): HTTP, JWT validation, GraphQL
parsing and execution, JSON serialization, and JPA (the Postgres side uses `JdbcTemplate`, which favours it
slightly). There is no concurrent load and no concurrent writing, and the whole data set fits in memory.

**Limits to keep in mind.**

- Both databases are containers on the same host as the test, so a round trip costs almost nothing. With a
  real network between service and database every round trip costs more: that hurt the 257-round-trip variant
  most, and it still counts against 7 round trips compared with 2.
- GitHub-hosted runners are shared and noisy, and there are three runs. Between the first two the same code
  moved by roughly 50 % (43 ms against 66 ms for the same variant). Read the numbers as orders of magnitude;
  differences of a few tenths of a millisecond mean nothing.
- The "before" and "after" of the batching are compared inside one run (run C), where the old per-parent reads
  are replayed through the use-case methods that still exist. They are not the removed resolver code itself:
  the staffing read of one shift now goes through the batch read with a single id.
- The week asked for lies at the start of the history. A week at the end could behave differently for the
  time-window indexes; that was not measured.
- Single-user latency says nothing about throughput under load, which is where scaling the query role
  separately would show. That was not measured either.

## Environment

GitHub-hosted `ubuntu-latest` runners (image `ubuntu-26.04`), job `tests (scheduling-service)`, all on
2026-11-20. As printed by the test in all three runs: 4 CPUs, Java 21.0.13, Linux amd64, MongoDB 8.0.38
(`mongo:8.0`), PostgreSQL 17.12 (`postgres:17-alpine`), both databases through Testcontainers.

| | run | commit | max heap | benchmark time | Maven build |
|---|---|---|---|---|---|
| Run C (after batching, PR #127, manual run with `benchmark` ticked) | <https://github.com/microservices-happens/shift-happens/actions/runs/44731371799> | `79360ae` | 3996 MB | 123.5 s | 3 min 40 s |
| Run A (before batching, PR #113, branch merged with main) | <https://github.com/microservices-happens/shift-happens/actions/runs/44723271982> | `b91184e` | 3996 MB | 87.36 s | 3 min 11 s |
| Run B (before batching, PR #113, same benchmark code, earlier) | <https://github.com/microservices-happens/shift-happens/actions/runs/44718872539> | `5c1c8e0` | 3926 MB | 63.49 s | 2 min 35 s |

Run C takes longer than A and B because it measures five variants instead of three. The pull-request build of
the same commit, which skips the benchmark, took 1 min 56 s
(<https://github.com/microservices-happens/shift-happens/actions/runs/44731373852>).

## Results

Copied unchanged from the job logs (milliseconds).

### After batching

**Run C** (44731371799):

| history | shifts stored | assignments stored | shifts in answer | assignments in answer | variant | round trips | p50 ms | p95 ms | mean ms | min ms | max ms |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 30 days | 900 | 4500 | 22 | 102 | read model (Mongo), per parent (resolvers before batching) | 257 | 67.91 | 85.85 | 69.79 | 60.09 | 106.97 |
| 30 days | 900 | 4500 | 22 | 102 | read model (Mongo), batched resolvers (deployed) | 7 | 3.23 | 3.80 | 3.30 | 2.91 | 9.63 |
| 30 days | 900 | 4500 | 22 | 102 | read model (Mongo), three reads (not deployed) | 4 | 1.87 | 2.21 | 1.91 | 1.65 | 3.60 |
| 30 days | 900 | 4500 | 22 | 102 | write model (Postgres), joins | 2 | 1.45 | 2.25 | 1.60 | 1.24 | 4.20 |
| 30 days | 900 | 4500 | 22 | 102 | deployed query over HTTP (JWT, GraphQL, JSON included) | 7 | 12.28 | 22.24 | 14.09 | 10.49 | 43.75 |
| 90 days | 2700 | 13500 | 22 | 102 | read model (Mongo), per parent (resolvers before batching) | 257 | 66.28 | 85.65 | 68.19 | 59.82 | 108.64 |
| 90 days | 2700 | 13500 | 22 | 102 | read model (Mongo), batched resolvers (deployed) | 7 | 3.50 | 4.04 | 3.55 | 3.25 | 4.70 |
| 90 days | 2700 | 13500 | 22 | 102 | read model (Mongo), three reads (not deployed) | 4 | 2.10 | 2.53 | 2.16 | 1.88 | 4.33 |
| 90 days | 2700 | 13500 | 22 | 102 | write model (Postgres), joins | 2 | 1.74 | 2.36 | 1.85 | 1.60 | 3.25 |
| 90 days | 2700 | 13500 | 22 | 102 | deployed query over HTTP (JWT, GraphQL, JSON included) | 7 | 11.40 | 20.38 | 12.73 | 9.55 | 32.66 |
| 365 days | 10950 | 54750 | 22 | 102 | read model (Mongo), per parent (resolvers before batching) | 257 | 66.83 | 75.40 | 67.71 | 59.98 | 94.83 |
| 365 days | 10950 | 54750 | 22 | 102 | read model (Mongo), batched resolvers (deployed) | 7 | 5.05 | 5.55 | 5.12 | 4.79 | 8.19 |
| 365 days | 10950 | 54750 | 22 | 102 | read model (Mongo), three reads (not deployed) | 4 | 3.41 | 4.01 | 3.51 | 3.02 | 8.69 |
| 365 days | 10950 | 54750 | 22 | 102 | write model (Postgres), joins | 2 | 2.20 | 2.61 | 2.28 | 2.06 | 7.79 |
| 365 days | 10950 | 54750 | 22 | 102 | deployed query over HTTP (JWT, GraphQL, JSON included) | 7 | 11.67 | 17.94 | 12.47 | 10.32 | 34.59 |

What run C shows:

- **Batching the resolvers took the deployed read path from 257 round trips to 7**, and its median from
  66 to 68 ms down to 3.23 to 5.05 ms: 13 to 21 times faster, measured in the same run on the same data.
- **The write model with joins is still the fastest variant at every size** (median 1.45 to 2.20 ms). The
  deployed read path takes 2.0 to 2.3 times as long at the median, 1.8 to 2.9 ms more.
- **The deployed path is not the floor for these collections.** Reading each collection once (4 round trips)
  took 1.87 to 3.41 ms, about 1.4 to 1.6 ms less than the deployed 7 round trips. The difference is that
  `requiredJobRoles`/`isUnderstaffed` and `assignments`/`employee` each read the assignments and the employees.
  Even that floor was slower than the joins (1.2 to 1.6 times the median).
- **Over HTTP the query took a median of 11 to 12 ms** (p95 18 to 22 ms) on the same host. This row includes
  the HTTP round trip, JWT validation, GraphQL execution and JSON on both ends, which no other row does, so it
  must not be compared with them. It does say that after batching the database reads (about 3 to 5 ms) are no
  longer the larger part of what a client waits for. There is no such row from before the batching.
- The three in-process variants with few round trips grew with the history (the deployed path from 3.23 to
  5.05 ms, Postgres from 1.45 to 2.20 ms). We did not look at the query plans, so the cause is not established.

### Before batching

Runs A and B are from PR #113, when the resolvers read one parent at a time. Their variant "as the resolvers
read it" is the variant "per parent (resolvers before batching)" of run C, and their "batched" is "three reads
(not deployed)". In these two runs the round trips were calculated, not counted; counted, the 3 is a 4 (see
[Method](#method)).

**Run A** (44723271982):

| history | shifts stored | assignments stored | shifts in answer | assignments in answer | variant | round trips | p50 ms | p95 ms | mean ms | min ms | max ms |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 30 days | 900 | 4500 | 22 | 102 | read model (Mongo), as the resolvers read it | 257 | 65.68 | 78.17 | 66.94 | 58.42 | 113.79 |
| 30 days | 900 | 4500 | 22 | 102 | read model (Mongo), batched | 3 | 2.02 | 2.48 | 2.11 | 1.82 | 6.56 |
| 30 days | 900 | 4500 | 22 | 102 | write model (Postgres), joins | 2 | 1.34 | 1.89 | 1.42 | 1.21 | 2.28 |
| 90 days | 2700 | 13500 | 22 | 102 | read model (Mongo), as the resolvers read it | 257 | 65.12 | 77.69 | 65.89 | 59.10 | 82.58 |
| 90 days | 2700 | 13500 | 22 | 102 | read model (Mongo), batched | 3 | 2.28 | 2.74 | 2.32 | 2.08 | 3.64 |
| 90 days | 2700 | 13500 | 22 | 102 | write model (Postgres), joins | 2 | 1.61 | 2.14 | 1.71 | 1.48 | 4.70 |
| 365 days | 10950 | 54750 | 22 | 102 | read model (Mongo), as the resolvers read it | 257 | 66.45 | 79.26 | 67.20 | 59.83 | 92.95 |
| 365 days | 10950 | 54750 | 22 | 102 | read model (Mongo), batched | 3 | 3.79 | 4.31 | 3.85 | 3.47 | 5.95 |
| 365 days | 10950 | 54750 | 22 | 102 | write model (Postgres), joins | 2 | 2.09 | 2.72 | 2.16 | 1.93 | 3.13 |

**Run B** (44718872539):

| history | shifts stored | assignments stored | shifts in answer | assignments in answer | variant | round trips | p50 ms | p95 ms | mean ms | min ms | max ms |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 30 days | 900 | 4500 | 22 | 102 | read model (Mongo), as the resolvers read it | 257 | 45.00 | 63.21 | 46.71 | 32.60 | 86.69 |
| 30 days | 900 | 4500 | 22 | 102 | read model (Mongo), batched | 3 | 1.55 | 2.26 | 1.69 | 1.30 | 5.84 |
| 30 days | 900 | 4500 | 22 | 102 | write model (Postgres), joins | 2 | 1.00 | 1.44 | 1.04 | 0.82 | 2.13 |
| 90 days | 2700 | 13500 | 22 | 102 | read model (Mongo), as the resolvers read it | 257 | 42.00 | 60.46 | 43.78 | 33.05 | 76.17 |
| 90 days | 2700 | 13500 | 22 | 102 | read model (Mongo), batched | 3 | 1.78 | 2.45 | 1.91 | 1.54 | 4.06 |
| 90 days | 2700 | 13500 | 22 | 102 | write model (Postgres), joins | 2 | 1.29 | 1.88 | 1.37 | 0.99 | 6.68 |
| 365 days | 10950 | 54750 | 22 | 102 | read model (Mongo), as the resolvers read it | 257 | 43.48 | 64.37 | 45.69 | 34.14 | 77.59 |
| 365 days | 10950 | 54750 | 22 | 102 | read model (Mongo), batched | 3 | 3.20 | 3.88 | 3.34 | 2.78 | 15.02 |
| 365 days | 10950 | 54750 | 22 | 102 | write model (Postgres), joins | 2 | 1.55 | 1.99 | 1.60 | 1.37 | 2.42 |

What the tables of runs A and B showed:

- The write model with joins was the fastest variant at every size, with a median between 1.00 and 2.09 ms and
  a p95 below 3 ms.
- The read model as it was resolved then was 28 to 49 times slower at the median (42 to 66 ms). Within a run
  its latency did not move with the history size, because it was dominated by the 257 round trips, which depend
  on the size of the answer and not on the size of the store.
- The same read model read once per collection was close to Postgres: 1.4 to 2.1 times its median, a
  difference of 0.5 to 1.7 ms. So the store was not the problem; the field-by-field resolution was.
- The absolute numbers differ between the runs by up to about 50 %; the order of the variants and the ratios
  between them do not.

## Conclusion

1. **Latency does not justify the read model at this scale, before or after batching.** One year of a
   500-employee organisation is small for PostgreSQL: the joins the read model avoids cost about 2 ms (p95
   below 3 ms), and the deployed read model needs about 5 ms for the same answer. The claim "documents avoid
   expensive joins" is not supported by our own numbers, and we should not make it.
2. **The N+1 was the large cost, and it is gone.** The nested GraphQL fields were resolved one parent at a
   time (257 round trips, about 66 ms). They are now batched per field with DataLoaders (7 round trips, about
   5 ms at one year of data), and tests keep the number of reads independent of the number of shifts
   (`GraphQlBatchingTest`, `GraphQlRoundTripsIT`). What is left between the deployed path and PostgreSQL is
   small in absolute terms (under 3 ms on one host) but real: the read model is still not a denormalised view.
   Shifts, assignments and employees are separate collections, so the query role does in several reads what
   PostgreSQL does in two joins. Embedding assignments and names in the shift document, or sharing one
   assignment read between the staffing and the `assignments` field, would close part of the gap; neither is
   done.
3. **What CQRS does give scheduling, and what this measurement does not cover:**
   - The query role has no connection to the write database (`AbstractQueryIT` starts it without
     Postgres), so the schedule stays readable while the command role or its database is down, and reads
     put no load on the transactional store.
   - The two roles are separate deployments that can be scaled separately. We have not measured
     throughput under load, so this is a property of the design, not a measured result.
   - The read side can change shape (GraphQL, AI suggestions, the employee copy) without migrations on the
     write schema.
   - The price is eventual consistency, a projection that has to be idempotent and commutative, a second
     database and, as measured, no latency gain over the write model.

An honest summary for the report: CQRS in scheduling is justified by availability, isolation and
independent deployment of the read side, and it meets the course requirement; it is not justified by query
latency, and at this size a single PostgreSQL database would answer the schedule query faster.

## Reproduce

The benchmark is off by default. Locally (needs Docker for Testcontainers):

```bash
./mvnw -B -f services/scheduling-service/pom.xml verify -Dcqrs.benchmark=true
# more rounds: -Dcqrs.benchmark.warmup=50 -Dcqrs.benchmark.iterations=1000
```

`cqrs.benchmark` is a property in the service's `pom.xml` (default `false`) that Failsafe passes to the test
JVM; `CqrsReadModelBenchmarkIT` is `@EnabledIfSystemProperty(named = "cqrs.benchmark", matches = "true")` and is
reported as skipped otherwise.

In CI the `ci` workflow has a manual input for it. Pull requests and pushes to main never run the benchmark;
a manual run does when `benchmark` is ticked (Actions -> ci -> Run workflow), or from the command line:

```bash
gh workflow run ci.yml --ref <branch> -f services=scheduling-service -f benchmark=true
```

The table is printed to the build log on lines starting with `CQRS-BENCH |`, written to
`target/failsafe-reports/cqrs-read-model-benchmark.md` (in CI: the `reports-scheduling-service` artifact) and
added to the GitHub Actions job summary. From CI:

```bash
gh run view <run-id> --log | grep 'CQRS-BENCH'
```

A new run prints new numbers; the tables above are the three runs linked under [Environment](#environment).
