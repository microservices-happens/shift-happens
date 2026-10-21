# Git, CI and review

## Branches and commits

- Branch names: `feat/<service>-<topic>`, `fix/<service>-<topic>`, `docs/<topic>`, `ci/<topic>`.
- Conventional commits scoped by service:
  `feat(scheduling): reject overlapping assignments`, `test(leave): cover ledger reversals`.
- Keep a PR single-purpose. Never commit directly to `main`.

## Contract-first

If a change touches `contracts/`, the PR description must list every affected service and their
owners must review. An event change is one PR that updates all of:

1. `contracts/asyncapi.yaml`
2. `contracts/events/catalog.md`
3. `contracts/events/payloads/<name>.schema.json`
4. the producing and consuming services

## CI gates (all required)

| Workflow / job | What it proves |
|---|---|
| `services.yml` → `ci` | Aggregate gate for all service jobs (always runs, so it is safe to require) |
| `service-<name>.yml` (8) | Isolated slice pipeline per service; re-runnable on its own |
| `system-tests.yml` | Aggregate cross-service cooperation tests |
| `nightly-shift-risk.yml` | Scheduled domain report (see `docs/ci-demo.md`) |
| `demo-matrix.yml`, `on-demand-demo.yml` | Matrix and API-triggered demonstrations |
| `contracts.yml` | OpenAPI, AsyncAPI, GraphQL SDL and JSON Schemas parse |
| `services.yml` → `unit-tests` | Surefire unit tests for each changed service |
| `services.yml` → `integration-tests` | Failsafe + Testcontainers for each changed service |
| `services.yml` → `images` | Image builds; Trivy finds no fixable HIGH/CRITICAL |
| `system-tests.yml` | Aggregate cross-service cooperation tests |
| `frontend.yml` | TypeScript typecheck and production build |
| `codeql.yml` | CodeQL static security analysis |
| `dependency-review.yml` | A PR cannot introduce a high-severity vulnerable dependency |

Path filters are intentional: a change to one service does not rebuild the others, but a change
under `contracts/` rebuilds every service because it implements or consumes a contract.

Do not weaken a workflow to make a PR green. Fix the code, or open a task and explain the gate in
the PR description.

## Secrets and configuration

- Never commit `.env`, keys or passwords. Update `.env.example` instead.
- CI uses `secrets.GITHUB_TOKEN`. Any new secret is documented in the README and added through
  repository settings.
- Kubernetes secrets are never committed; `k8s/base/config.yaml` holds non-secret values only.
