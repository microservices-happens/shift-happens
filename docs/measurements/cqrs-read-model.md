# CQRS read model: schedule query latency with and without it (P3.6)

**Question:** is the schedule query faster on the MongoDB read model of `scheduling-query` than it would be
on the normalised PostgreSQL write model of `scheduling-command`?

**Short answer:** no, not at this data volume. With one year of data the write model answers in a median of
2.09 ms, the read model in 3.79 ms when it is read in batches, and in 66.45 ms the way the GraphQL resolvers
read it today (1.55, 3.20 and 43.48 ms in an earlier run). Latency does not justify CQRS in scheduling; what does is listed under
[Conclusion](#conclusion).

## Method

The benchmark is an integration test,
[`CqrsReadModelBenchmarkIT`](../../services/scheduling-service/src/test/java/dk/ek/shift_happens/scheduling/CqrsReadModelBenchmarkIT.java).
It runs with the other `*IT` tests in `mvn verify`, so every CI run of scheduling-service repeats it.

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

**Three ways of answering it.**

| variant | what it does | database round trips |
|---|---|---|
| read model (Mongo), as the resolvers read it | The same `SchedulingQueryService` calls, in the same number, as `SchedulingGraphQlController` makes for that query: `shifts`, then per shift `requiredJobRoles` (3 reads), `isUnderstaffed` (3 reads) and `assignments` (1 read), then one employee read per assignment. **This is what the query role does today.** | 257 |
| read model (Mongo), batched | The same collections in three reads: the shifts, their assignments with `shiftId $in`, the employees with `_id $in`. **Not deployed**; it separates the cost of the store from the cost of resolving field by field. | 3 |
| write model (Postgres), joins | Two SQL statements on plain JDBC: `shift LEFT JOIN shift_required_job_role`, and `shift_assignment JOIN shift JOIN employee_replica`. These are the joins the read model avoids. | 2 |

**Same answer.** Before timing, at every size, the test asserts that all three variants return an equal list
(shift fields, required roles with `assignedCount`, `isUnderstaffed`, assignments with employee names). This
is the only assertion; no latency is asserted, so the benchmark cannot make the build flaky.

**Timing.** Per size: 30 warm-up rounds, then 200 measured rounds. One round runs each variant once, so
drift on the runner hits all three alike. Each call is timed with `System.nanoTime()` on one thread.
Percentiles are nearest-rank.

**Included:** the database round trips and mapping the rows or documents to plain Java records.

**Not included:** HTTP, JWT validation, GraphQL parsing and execution, JSON serialization, and JPA (the
Postgres side uses `JdbcTemplate`, which favours it slightly). There is no concurrent load and no concurrent
writing, and the whole data set fits in memory.

**Limits to keep in mind.**

- Both databases are containers on the same host as the test, so a round trip costs almost nothing. With a
  real network between service and database, the 257-round-trip variant gets worse and the other two barely
  change.
- GitHub-hosted runners are shared and noisy, and there are two runs. Between them the same code moved by
  roughly 50 % (43 ms against 66 ms for the same variant). Read the numbers as orders of magnitude;
  differences of a few tenths of a millisecond mean nothing.
- The week asked for lies at the start of the history. A week at the end could behave differently for the
  time-window indexes; that was not measured.
- Single-user latency says nothing about throughput under load, which is where scaling the query role
  separately would show. That was not measured either.

## Environment

Two CI runs of PR #113 on GitHub-hosted `ubuntu-latest` runners (image `ubuntu-26.04`), job
`tests (scheduling-service)`, both on 2026-11-20. As printed by the test in both: 4 CPUs, Java 21.0.13,
Linux amd64, MongoDB 8.0.38 (`mongo:8.0`), PostgreSQL 17.12 (`postgres:17-alpine`), both databases through
Testcontainers.

| | run | commit | max heap | benchmark time | Maven build |
|---|---|---|---|---|---|
| Run A (current, branch merged with main) | <https://github.com/microservices-happens/shift-happens/actions/runs/44723271982> | `b91184e` | 3996 MB | 87.36 s | 3 min 11 s |
| Run B (earlier, same benchmark code) | <https://github.com/microservices-happens/shift-happens/actions/runs/44718872539> | `5c1c8e0` | 3926 MB | 63.49 s | 2 min 35 s |

## Results

Copied unchanged from the job logs (milliseconds).

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

What the tables show, in both runs:

- **The write model with joins was the fastest variant at every size**, with a median between 1.00 and
  2.09 ms and a p95 below 3 ms.
- **The read model as it is resolved today was 28 to 49 times slower at the median** (42 to 66 ms). Within
  a run its latency did not move with the history size, because it is dominated by the 257 round trips,
  which depend on the size of the answer and not on the size of the store.
- **The same read model read in three round trips was close to Postgres**: 1.4 to 2.1 times its median, a
  difference of 0.5 to 1.7 ms. So the store is not the problem; the field-by-field resolution is.
- Both batched variants grew with the history (in run A, Mongo from 2.02 to 3.79 ms and Postgres from 1.34
  to 2.09 ms). We did not look at the query plans, so the cause is not established.
- The absolute numbers differ between the runs by up to about 50 %; the order of the variants and the
  ratios between them do not.

## Conclusion

1. **Latency does not justify the read model at this scale.** One year of a 500-employee organisation is
   small for PostgreSQL: the joins the read model avoids cost about 2 ms (p95 below 3 ms). The claim "documents avoid
   expensive joins" is not supported by our own numbers, and we should not make it.
2. **The read model is not a denormalised view today.** Shifts, assignments and employees are separate
   collections, and the nested GraphQL fields are resolved one parent at a time. That N+1 pattern, not
   MongoDB, is what makes the deployed read path the slowest of the three. Batching the nested resolvers
   (Spring GraphQL `@BatchMapping`) or embedding assignments and names in the shift document would remove
   most of it. This is a follow-up; this PR changes no production code.
3. **What CQRS does give scheduling, and what this measurement does not cover:**
   - The query role has no connection to the write database (`AbstractQueryIT` starts it without
     Postgres), so the schedule stays readable while the command role or its database is down, and reads
     put no load on the transactional store.
   - The two roles are separate deployments that can be scaled separately. We have not measured
     throughput under load, so this is a property of the design, not a measured result.
   - The read side can change shape (GraphQL, AI suggestions, the employee copy) without migrations on the
     write schema.
   - The price is eventual consistency, a projection that has to be idempotent and commutative, a second
     database and, as measured, no latency gain.

An honest summary for the report: CQRS in scheduling is justified by availability, isolation and
independent deployment of the read side, and it meets the course requirement; it is not justified by query
latency, and at this size a single PostgreSQL database would answer the schedule query faster.

## Reproduce

```bash
./mvnw -B -f services/scheduling-service/pom.xml verify          # needs Docker (Testcontainers)
# more rounds: -Dcqrs.benchmark.warmup=50 -Dcqrs.benchmark.iterations=1000
```

The table is printed to the build log on lines starting with `CQRS-BENCH |`, written to
`target/failsafe-reports/cqrs-read-model-benchmark.md` (in CI: the `reports-scheduling-service` artifact) and
added to the GitHub Actions job summary. From CI:

```bash
gh run view <run-id> --log | grep 'CQRS-BENCH'
```

A new run prints new numbers; the tables above are the two runs linked under [Environment](#environment).
