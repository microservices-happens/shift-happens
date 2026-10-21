# Controller DTOs — Rationale

## Requirement

> "Use DTO objects in the controllers — do not expose the models to the outside."
> — Tomas

This document explains why the SQL/JPA controllers were migrated from returning
raw persistence entities to returning DTOs, and the design choices behind it.

## Why DTOs at all

1. **Separation of concerns / encapsulation.**
   A JPA `@Entity` is a *persistence* concern — it maps to a database table.
   An API response is a *contract* concern — it is what clients depend on.
   Returning the entity directly fuses the two: any schema change (renamed
   column, new field, changed relation) instantly becomes a breaking API
   change. The DTO is a stable boundary between the database and the API.

2. **Security — exposure becomes opt-in.**
   A raw entity serializes *every* field, including fields added later.
   With raw entities the team had to scatter `@JsonIgnore` /
   `@JsonProperty(WRITE_ONLY)` annotations onto the persistence model to plug
   leaks. With a DTO, a field only reaches the client if it is explicitly
   declared in the record. Concrete wins in this project:
   - `EmployeeDto.loginPassword` is `WRITE_ONLY` — accepted on create/update,
     never serialized back.
   - `EmployeeContractDto` omits `salaryAmount` entirely, so it cannot leak.

3. **Fail-safe by default.**
   New entity fields stay invisible to the API until a developer consciously
   adds them to the DTO.

## Design choices

| Decision | Rationale |
|---|---|
| One Java `record` per entity | Records are immutable and concise — a DTO by language design. Matches the existing `EmployeeShiftOverviewDto` view pattern, so the codebase stays consistent. |
| `static from(entity)` + `toEntity()` on the DTO | Mapping logic lives in one obvious place, next to the fields. No mapper framework (e.g. MapStruct) — manual mapping is explicit and easy to explain at this project size. |
| One DTO for both request and response | A deliberate trade-off: roughly halves the file count vs. separate request/response DTOs. Limitation: `id` is meaningful on a response but ignored on a create request. |
| Services still operate on entities; only controllers changed | Keeps the migration low-risk. The DTO is purely a web-boundary concern; business logic in the service layer is untouched. |
| Foreign keys stay as scalar ids (`departmentId`, not a nested `Department`) | The entities already use scalar FK columns, so the DTO mirrors them. Also avoids serializing lazy `@ManyToOne` relations, which can cause extra queries or `LazyInitializationException`. |

## Known trade-offs (stated honestly)

- **One DTO for input + output is a compromise.** A stricter design uses
  separate request/response DTOs. We chose simplicity for scope reasons; we
  would split them if create and read fields diverged.
- **Manual mapping has boilerplate** — every field is copied twice. Acceptable
  at 17 entities; at 100+ a mapper framework would be justified.
- **`Optional<XDto>` return types** (Shift, ShiftApproval, etc.) were kept
  as-is to keep the migration behavior-preserving.

## MongoDB controllers

The same approach was applied to the **8 MongoDB controllers**. DTOs there are
named `XDocumentDto` to avoid clashing with the JPA-side `XDto` in the parent
package.

The Mongo documents fall into two tiers:

- **Flat reference documents (5)** — Department, JobRole, LeaveType,
  WorkLocation, AuditLog. Same trivial flat-DTO treatment as the JPA entities.
- **Denormalised aggregate documents (3)** — Employee, Shift, Leave. These are
  deep trees of embedded sub-documents (a Mongo document is deliberately
  designed as a read model, joined at write time instead of read time). Each
  embedded sub-document is mirrored by a nested DTO `record` — about 19 nested
  records in total.

### Is mirroring the nested documents worth it?

Honest answer: the value varies.

- For `EmployeeDocument` it is real — `loginPassword` was exposed with **no**
  protection at all (unlike the JPA `Employee`), and `salaryAmount` sat
  unprotected inside an embedded contract. The DTO closes both:
  `loginPassword` is write-only, `salaryAmount` is dropped.
- For `ShiftDocument` and `LeaveDocument` there is no sensitive data, so their
  nested DTOs are mostly **consistency** value: every controller returns a
  DTO, with no exceptions to explain. The requirement ("use DTO objects in the
  controllers") is unconditional, and a complete, uniform rule is easier to
  defend than a judgement-based carve-out.

A DTO is an API-boundary concern, not a SQL-specific one — it applies equally
to JPA entities, Mongo documents and Neo4j nodes.

## Neo4j controllers

The same approach was applied to the **10 Neo4j controllers**. DTOs there are
named `XNodeDto` to avoid clashing with the JPA-side `XDto` and the Mongo-side
`XDocumentDto`.

All 10 Neo4j nodes are **flat** — no `@Relationship` mappings, no embedded
structure — so every DTO is a flat record, no nested mirroring needed. There
are also **no sensitive fields** (unlike Mongo, `EmployeeNode` has no
password), so no write-only / dropped-field handling was required.

Two details specific to Neo4j:

- 5 of the 10 controllers are **read-only** (`getAll` / `getById`), so their
  DTOs only need a `from()` factory — no `toEntity()`.
- `ShiftSwapNeo4jController` also has three Cypher-query endpoints that return
  `List<Map<String, Object>>` projections. A `Map` is a query result, not a
  persistence model, so those endpoints are left as-is — consistent with
  `Neo4jInsightsController`, which was excluded for the same reason.

## Known follow-ups (out of scope here)

- **Service layer** — added in a follow-up branch; see `docs/service-layer.md`.
- **Mongo and Neo4j controllers have no `@PreAuthorize`** — they are
  unauthenticated, unlike the JPA controllers. A separate security concern.

## Scope

Migrated: the **17 SQL/JPA controllers**, the **8 MongoDB controllers**, and
the **10 Neo4j controllers**. `AuthController` already uses POJOs;
`MigrationController` and `Neo4jInsightsController` do not expose entities.
