# The CI pipeline in one page

There are **four workflow files** in `.github/workflows/`. That is on purpose: one gate for pull
requests, one aggregate system test, one static analysis, one scheduled report.

| File | Runs when | Jobs | What it proves |
|---|---|---|---|
| `ci.yml` | every PR, every push to `main`, manual | `changes` → `tests` (matrix) → `images` (matrix) → `ci`, plus `contracts` / `frontend` when those paths change | the service compiles, its unit **and** integration tests pass, its image builds and is scanned |
| `system-tests.yml` | push to `main`, nightly 02:00 UTC, manual | `build-images` (matrix) → `cooperation-tests` | two real services + RabbitMQ cooperate in containers |
| `codeql.yml` | every PR, push to `main`, weekly | `analyze` | static security analysis of the Java code |
| `nightly-shift-risk.yml` | nightly 03:00 UTC, manual | `report` | a scheduled, domain-specific report: understaffing, double booking, unreleased leave |

**`ci` is the only check to require in branch protection.** It always runs and turns any red matrix
row into a red gate.

## What runs in parallel

- `tests` is a matrix: one row per changed service. A change under `contracts/` therefore runs all
  eight slices at the same time.
- `images` is a second matrix for the same services, running alongside `tests`.
- `system-tests` builds its two images in a matrix, then a single test job `needs` both builds.

## Data shared between jobs

| Mechanism | Where | What travels |
|---|---|---|
| `actions/cache`, key `m2-<os>-jdk21-v1` | `ci.yml`, `codeql.yml` | the Maven repository, shared by all services (one download per OS/JDK) |
| artifacts | `ci.yml` (test reports), `system-tests.yml` (images via `docker save`/`docker load`), `nightly-shift-risk.yml` (report) | files passed from one job to another |
| `$GITHUB_STEP_SUMMARY` | all | the human-readable result on the run page |

## How to run things

```bash
# everything that changed (normal PR / main behaviour)
gh workflow run ci
# one slice only, including its image build and Trivy scan
gh workflow run ci -f services=identity-service
# the cross-service system tests
gh workflow run system-tests
# the scheduled domain report, with an issue if it finds something
gh workflow run nightly-shift-risk -f horizon_days=7 -f create_issue=false
```

Locally, without GitHub: `mvn -B -f services/<service>/pom.xml verify` (unit + integration) and
`mvn -B -f tests/cooperation/pom.xml verify` (system tests, needs the images built first — see
`tests/cooperation/README.md`).

## What to point at during a demo

1. Open a PR: one run, the `ci` job graph with the `tests` and `images` matrices side by side.
2. Open the `image (...)` job and scroll to the Trivy table.
3. Open the run summary: the table written by the `ci` aggregate job.
4. Push to `main` (or run it manually): `system-tests`, where `build-images` fans out and
   `cooperation-tests` waits for both.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `status code: 403` from Maven Central | transient CDN rejection. Re-run the failed job (`gh run rerun <id> --failed`); the `-Dmaven.wagon.http.retryHandler.count=3` flag is already set on every Maven call |
| `Unable to resolve action ... trivy-action@0.28.0` | its tags carry a `v` prefix. We pin `aquasecurity/trivy-action@v0.36.0` |
| Trivy finds HIGH CVEs but the job is green | intended: the scan is report-only while the services are skeletons. Set the repository variable `TRIVY_EXIT_CODE=1` to make it blocking (P6.7) |
| `system-tests` fails with "image ... not found" | the matrix in `system-tests.yml` lists the images it builds. Add the service the test needs (comment at the top of the file) |
| A required `ci` check stays "Expected" | the PR must touch a path the workflow listens to, or run it manually with `-f services=...` |

## What was removed, and how to bring it back

Per-service workflow files, a separate contracts/frontend workflow, the OS×JDK matrix, the
API-triggered demo and the dependency-review action were removed to keep this to one page. Each was
a thin wrapper around what `ci.yml` already does. If you want the OS×JDK matrix back for the
presentation, it is a copy of the `tests` job with
`strategy: matrix: { os: [ubuntu-latest, windows-latest], java: ['21','25'] }` and
`continue-on-error` on the experimental cell.
