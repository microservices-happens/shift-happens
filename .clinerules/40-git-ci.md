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

| Workflow | What it proves |
|---|---|
| `ci.yml` → `tests` | Surefire unit tests + Failsafe integration tests for every changed service (Testcontainers for the `*IT`) |
| `ci.yml` → `images` | Every changed service builds an image; Trivy scans it (blocking only when the repository variable `TRIVY_EXIT_CODE=1`) |
| `ci.yml` → `contracts` / `frontend` | Contracts parse; the frontend typechecks and builds |
| `ci.yml` → `ci` | Aggregate gate. Always runs, so it is the one check to require on `main` |
| `system-tests.yml` | Two or more services plus RabbitMQ cooperate in containers |
| `codeql.yml` | Static security analysis (findings in the Security tab) |
| `nightly-shift-risk.yml` | Scheduled domain report (`docs/ci-demo.md`) |

Path filters are intentional: a change to one service does not rebuild the others, but a change
under `contracts/` rebuilds every service because it implements or consumes a contract.

## Maven steps

Use plain `mvn -B ...` steps with `-Dmaven.wagon.http.retryHandler.count=3` (Maven Central
occasionally answers a transient `403` to shared runner IPs) and the shared cache key
`m2-<os>-jdk21-v1`. `mvn verify` runs the unit tests (Surefire) and then the integration tests
(Failsafe); `mvn test` runs only the unit tests. Do not reintroduce a per-service workflow: one
matrix row in `ci.yml` is enough, and it keeps the required-check story to a single `ci` job.

Do not weaken a workflow to make a PR green. Fix the code, or open a task and explain the gate in
the PR description.

## Secrets and configuration

- Never commit `.env`, keys or passwords. Update `.env.example` instead.
- CI uses `secrets.GITHUB_TOKEN`. Any new secret is documented in the README and added through
  repository settings.
- Kubernetes secrets are never committed; `k8s/base/config.yaml` holds non-secret values only.
