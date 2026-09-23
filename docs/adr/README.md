# Architecture Decision Records

One short file per significant decision: `NNNN-title.md`, using [`template.md`](template.md). ADRs are never edited after they are accepted. A changed decision gets a new ADR that supersedes the old one.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-events-only-between-services.md) | Services communicate only through RabbitMQ events | Accepted |
| [0002](0002-shift-swaps-inside-scheduling.md) | Shift swaps live inside Scheduling; the saga is leave approval | Accepted |
| [0003](0003-graphql-on-scheduling-read-side.md) | GraphQL on the Scheduling query role; gateway without logic | Accepted |

Further candidates are listed in `docs/architecture.md` §4.5.
