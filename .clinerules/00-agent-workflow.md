# Agent workflow (read this first)

These rules apply to every task in this repository unless the user explicitly overrides them.

## Before you change code

1. Read the contract and the service README for the area you touch:
   `contracts/README.md`, `contracts/openapi/<service>.yaml`, `contracts/asyncapi.yaml`,
   `services/<service>/README.md`.
2. Read `docs/architecture.md` §4 before any design decision. If the architecture document and
   the monolith disagree, ask; do not silently pick one.
3. Prefer the narrowest change. One service per PR unless a contract changes.

## Non-negotiable boundaries

- `contracts/` is the source of truth and changes by pull request only. Do not edit it unless the
  user explicitly asks. If an implementation needs a contract change, say so first.
- No service reads another service's database and no service calls another service
  synchronously. Data is replicated from events (the replica pattern in each service README).
- The monolith (`src/`, root `pom.xml`, `docker-compose*.yml` without `.microservices`) is
  reference code. Do not refactor it. `*_old.*`, `scripts_old/` and `docs/old/` are archives.
- Do not add a shared Maven parent or shared library. Patterns are copied per service on purpose
  so every service stays independently deployable.

## Commands (run from the repository root)

| Task | Command |
|---|---|
| Unit tests for one service | `mvn -B -f services/<svc>/pom.xml test` |
| Integration tests only | `mvn -B -f services/<svc>/pom.xml verify "-Dskip.unit.tests=true"` |
| Everything for one service | `mvn -B -f services/<svc>/pom.xml verify` |
| Cooperation (system) tests | `mvn -B -f tests/cooperation/pom.xml verify` |
| Frontend checks | `npm --prefix frontend ci` then `npm --prefix frontend run typecheck` and `npm --prefix frontend run build` |
| Infrastructure only | `docker compose -f docker-compose.microservices.yml up -d` |
| Full local stack | `docker compose -f docker-compose.microservices.yml --profile app up --build` |

The repository wrapper works too: `.\mvnw.cmd -B -f services/identity-service/pom.xml test`
(and `./mvnw ...` on Linux/macOS).

> PowerShell splits the property `-Dskip.unit.tests=true` into two arguments. Always quote it in
> PowerShell (`"-Dskip.unit.tests=true"`); bash in CI does not need the quotes.

## Definition of a finished change

- The narrowest command above was run and its result is quoted in the summary.
- A new business rule has a unit test; new messaging or database code has an `*IT` test.
- Service README and/or `docs/` updated in the same change when behaviour or a contract changed.
- Nothing was skipped silently. If a test is disabled, name the task ID (`P3.1`) that enables it.

## Context hygiene

- `docs/DLS Report-2026-10-21.md` is ~2 MB because of embedded images. Never read it whole;
  search it and read line ranges.
- Do not read `target/`, `node_modules/`, `backups/`, `*.pdf` or anything in `.clineignore`.
- To understand legacy behaviour, search for a class name instead of reading whole packages.
