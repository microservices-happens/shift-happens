# Diagrams

| Document | What it explains |
|---|---|
| [async-services.md](async-services.md) | Database per service, event-only integration, the employee replica pattern, tombstones and idempotent consumers |
| [cqrs-and-saga.md](cqrs-and-saga.md) | Command/query roles, the outbox → projection path that keeps the read database current, the leave-approval saga, compensation and the state machine |
| [observability-dlq-recovery.md](observability-dlq-recovery.md) | Where logs/metrics/queue data come from, the current vs planned stack, DLQ mechanics, staged crash recovery and scaling |
| [cqrs-read-model.md](../measurements/cqrs-read-model.md) | Measured comparison of the scheduling read model and the PostgreSQL write model |
| `*.drawio` / `*.png` | Compose, Kubernetes, CI/CD and cloud deployment diagrams |

The Mermaid blocks render in GitHub, the IDE preview and the GitHub Wiki. The `drawio` sources are the
editable originals for the deployment diagrams; keep the exported PNG next to them.
