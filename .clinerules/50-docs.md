# Documentation and contracts

## Service READMEs

Each `services/<name>/README.md` is the source for report §2.2 (purpose, APIs, events, data, key
logic, scaling/failure). Update the "Done when" checkboxes in the same PR that makes them true.
New services or responsibilities also update the tables in `contracts/README.md` and
`docs/project-plan.md`.

## API documentation

- REST: `contracts/openapi/<service>.yaml` is the contract. The springdoc output must match it;
  annotate controllers so operation ids and schemas line up.
- GraphQL: `contracts/graphql/scheduling.graphql` is the schema. Add fields, mark old ones
  `@deprecated`, never version the endpoint.
- Events: routing key `.vN`, envelope + payload JSON Schema, AsyncAPI entry and the catalog table
  are updated together.

## Decisions

- ADRs live in `docs/adr/NNNN-title.md`. Write one for decisions that are expensive to reverse
  (CQRS boundaries, saga orchestration vs choreography, broker topology, identity provider,
  read-model choice).
- `docs/project-plan.md` owns task IDs (`P3.1`). Reference them in PR titles, commits and code
  comments that mark unfinished work.
- `docs/implementation-plan.md` owns the order in which the service skeletons are filled.

## General

- Write code, comments, commits and documentation in English. The report may be in Danish.
- The installation procedure in the root README must keep working when Compose, ports or
  environment variables change.
- Prefer diagrams and tables over prose that duplicates the contracts.
