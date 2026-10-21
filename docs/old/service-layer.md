# Service Layer — Rationale

## Requirement

> "Apply layered architecture: controllers -> services -> models / repositories."
> — assignment brief

## What was done

Before this change only **5 of 17 JPA controllers** had a service; the Mongo
and Neo4j controllers had none and called their repositories directly. Every
controller now goes through a service:

| Store | Controllers | Services added |
|---|---|---|
| JPA   | 17 | 12 (5 already existed) |
| Mongo | 8  | 8 |
| Neo4j | 10 | 9 (`ShiftSwapNeo4jService` already existed; extended) |

The dependency chain is now uniformly `controller -> service -> repository`
for all 35 controllers.

## Design

- **Thin delegating services.** Each service is `@Service @RequiredArgsConstructor`,
  holds the repository, and exposes `findAll / findById / create / update /
  delete`. No business logic was invented — adding any would be speculative.
- **`findById` / `update` return `Optional`.** The service stays free of HTTP
  concerns; the controller decides the HTTP response (404, empty body, etc.).
  This follows the existing `LeaveRequestService.patch` precedent. The older
  `DepartmentService` throws `ResponseStatusException` internally instead — a
  minor pre-existing inconsistency, left untouched to keep this change surgical.
- **Behaviour preserved.** Field-copy logic in `update` moved verbatim from the
  controllers into the services; every endpoint's status codes are unchanged.
- **Neo4j `ShiftSwap`.** The existing `ShiftSwapNeo4jService` was extended with
  `findAll` / `findById` and the two Cypher candidate-queries, so the controller
  no longer talks to `Neo4jClient` or the repository directly.

## Known follow-ups (still out of scope)

- **No `@PreAuthorize` on Mongo / Neo4j controllers** — a separate security
  concern.
- **`DepartmentService` style divergence** — older services throw from
  `findById`; new ones return `Optional`. Could be aligned later.
