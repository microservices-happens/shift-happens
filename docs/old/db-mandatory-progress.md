# DB Mandatory — Progress & Handoff

State of the DTO + layered-architecture work on the `shift_happens` project.
Read this first when resuming. Companion docs: `dto-rationale.md`,
`service-layer.md`.

## Assignment requirements — status

From the assignment: *"Apply layered architecture: controllers -> services ->
models / repositories ... use DTO objects in the controllers. Do not expose
the models to the outside."*

| Requirement | Status |
|---|---|
| DTOs in all controllers (no raw entities exposed) | ✅ Done — JPA, Mongo, Neo4j |
| Layered architecture (controllers → services → repositories) | ✅ Done — all 35 controllers |
| `/mongo/audit_log` admin-restriction | ⬜ **Open — the one remaining task** |

## The open PR stack (4 PRs, stacked — merge bottom-up)

| PR | Branch | Base | Scope |
|---|---|---|---|
| #109 | `feature/controller-dtos` | `main` | DTOs for 17 SQL/JPA controllers |
| #111 | `feature/mongo-controller-dtos` | `feature/controller-dtos` | DTOs for 8 MongoDB controllers |
| #112 | `feature/neo4j-controller-dtos` | `feature/mongo-controller-dtos` | DTOs for 10 Neo4j controllers |
| #114 | `feature/service-layer` | `feature/neo4j-controller-dtos` | Service layer for all 35 controllers |

**Merge order: #109 → #111 → #112 → #114.** Each PR's base retargets to `main`
automatically as the one below it merges. Each PR shows only its own diff.

Repo: `https://github.com/Luke3520/shift_happens.git`

## Conventions established (follow these if extending the work)

- **DTOs are Java `record`s** with a static `from(...)` factory and a
  `toEntity()` method. Naming: JPA `XDto`, Mongo `XDocumentDto`, Neo4j
  `XNodeDto` (distinct names avoid cross-package clashes).
- **Sensitive fields:** `loginPassword` is write-only (`@JsonProperty(WRITE_ONLY)`);
  `salaryAmount` is dropped from DTOs entirely.
- **Services** are thin `@Service @RequiredArgsConstructor` delegates exposing
  `findAll / findById / create / update / delete`. New services' `findById`
  and `update` return `Optional`; the controller maps to the HTTP response.
- No co-author / "Generated with Claude Code" trailers in commits or PRs.

## THE REMAINING TASK — fix `/mongo/audit_log` authorization

**Problem:** `SecurityConfig` (`auth/SecurityConfig.java`) secures all requests
by URL. Its rule `/auditlogs/**` → `hasRole("ADMINISTRATOR")` restricts the JPA
audit log to admins. But `AuditLogMongoController` is mapped at
`/mongo/audit_log` — which that rule does NOT match. So the Mongo audit log is
readable by any authenticated user (including a plain EMPLOYEE). Inconsistent
with the JPA side.

**Fix:** in `SecurityConfig.securityFilterChain`, add a matcher for the Mongo
audit-log path next to the existing `/auditlogs/**` rule:

```java
.requestMatchers("/auditlogs/**").hasRole("ADMINISTRATOR")
.requestMatchers("/mongo/audit_log/**").hasRole("ADMINISTRATOR")  // add this
```

(There is no Neo4j audit-log controller, so nothing is needed there.)

**Do it as a new branch/PR** stacked on `feature/service-layer`
(`feature/mongo-auditlog-auth` → base `feature/service-layer`). Verify with
`mvn test` (expect 6/6 pass, `BUILD SUCCESS`).

## Deliberately NOT done (defensible non-goals)

- **`@PreAuthorize` on Mongo/Neo4j controllers** — redundant. `SecurityConfig`'s
  filter chain already authenticates every request and requires ADMIN/MANAGER
  for POST/PUT/PATCH/DELETE on `/**`. The JPA `@PreAuthorize` annotations are
  belt-and-suspenders; replicating them adds no real access control.
- **Per-row employee-scoping on Mongo/Neo4j** — the JPA side uses `AuthHelper`
  so an EMPLOYEE sees only their own rows. Mongo/Neo4j lack this. Left as a
  known limitation: those endpoints are parallel demo surfaces, not consumed
  by the frontend.
- **Consumer-driven / view-specific DTOs** — the 3 denormalised Mongo
  aggregates are mirrored 1:1. Best practice would be smaller, use-case-shaped
  DTOs, but there is no frontend consumer to design against. See
  `dto-rationale.md`.
