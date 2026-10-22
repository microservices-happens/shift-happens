# CI/CD demo guide

This document explains the pipeline set, what each workflow demonstrates, and how to drive the demo.
Everything here works with the current skeletons: no service has to be implemented for the pipelines
to run.

## 1. The pipeline set

| Workflow | Trigger | What it shows |
|---|---|---|
| `_service-ci.yml` | called by the workflows below | The reusable slice pipeline: build → unit tests → (optional) integration tests → summary. Maven cache, jar artifact shared with the next job, reports collected in a fan-in job |
| `service-<name>.yml` (8 files) | push to a feature branch touching that service or `contracts/`, or manual | One isolated run per service, so a member can run and **re-run only their slice**. No other service is needed |
| `services.yml` | every PR, push to `main`, or manual (`services` input) | The PR gate: detects changed services, runs the slice pipeline for each in a matrix, builds+scans images, and a `ci` job aggregates everything into one required check. A manual run skips the path filter, so the image build and scan can be demonstrated on demand |
| `system-tests.yml` | manual, nightly, and after `services` succeeds on `main` | The **aggregate** pipeline: builds several service images in parallel, shares them as artifacts, loads them and runs the cross-service cooperation tests (`tests/cooperation`) |
| `nightly-shift-risk.yml` | cron 03:00 UTC + manual | A **scheduled domain pipeline**: MySQL service container + seed data → shift-risk report → artifact, run summary, job output, optional GitHub issue |
| `demo-matrix.yml` | manual, or a push touching the file | `strategy.matrix` fan-out: Ubuntu + Windows × JDK 21 + 25, `continue-on-error` for experimental cells, OS-specific steps, per-cell artifacts, fan-in job |
| `on-demand-demo.yml` | `repository_dispatch` (`shift-happens-demo`) or manual | **API-triggered** run: reads `client_payload`, maps it to job outputs, validates it, then calls the reusable slice pipeline |
| `contracts.yml` | `contracts/**` | Contract lint (OpenAPI, AsyncAPI, GraphQL, JSON Schema) |
| `codeql.yml`, `dependency-review.yml` | PR / weekly | Static security analysis and vulnerable-dependency gate |
| `frontend.yml` | `frontend/**` | Typecheck + production build of the React app |

## 2. Isolation and re-running

* A push to `feature/scheduling-overlap` that touches `services/scheduling-service/**` starts **one**
  workflow: `service-scheduling`. The run has its own name, its own logs and its own artifacts.
* Re-running is per run: Actions → the run → **Re-run jobs** (or `gh run rerun <run-id> --failed`).
  Nothing else is re-executed, so one flaky integration test does not cost you the other seven
  services.
* A change under `contracts/**` fans out: all eight `service-*` workflows start in parallel, because
  a contract change can affect every slice. That is intentional and easy to show in the Actions tab.
* Pull requests are gated by `services.yml` (matrix over the changed services) with the single
  aggregate check `services / ci`, because GitHub cannot compose checks across workflows: a
  path-filtered workflow simply does not report on a PR that does not touch its paths, and an
  unreported required check blocks the merge.

```
push to a branch        -> service-<name>             (1 run, your slice)
pull request            -> services                   (changed slices + ci aggregate check)
contract change (push)  -> service-*                  (8 runs in parallel)
merge to main           -> services -> system-tests    (workflow_run chaining)
night, 03:00 UTC        -> nightly-shift-risk          (domain report)
```

## 3. Driving the demo

### 3.1 Parallel slices (2 min)

1. Edit anything under `services/identity-service/` on a branch and push.
2. Actions tab: `service-identity` is running; open it and show the job graph
   `build + unit tests → integration tests → summary`.
3. Open the **Summary** of the run: it lists the artifacts collected from the other jobs.
4. Show the cache: run it twice, the second run logs `Cache restored from key: m2-Linux-jdk21-…`.

### 3.2 Fan-out on a contract change (2 min)

1. Touch `contracts/README.md` (whitespace is enough) and push.
2. Actions tab: eight `service-*` runs start at the same time — parallel slices, isolated runs.

### 3.3 The aggregate system test (3 min)

```bash
gh workflow run system-tests -f services="identity-service workforce-service"

# or through the API
curl -X POST -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/microservices-happens/shift-happens/actions/workflows/system-tests.yml/dispatches \
  -d '{"ref":"main","inputs":{"services":"identity-service workforce-service"}}'
```

Then show in the run:

* `plan` prints the matrix it will build,
* `build image (identity-service)` and `build image (workforce-service)` run **in parallel**,
* each build exports its image as an artifact (`docker save`),
* `cooperation tests` **needs** both builds, downloads every `image-*` artifact, loads them and runs
  the real cooperation tests against RabbitMQ and two services.

### 3.4 Scheduled domain pipeline (2 min)

```bash
gh workflow run nightly-shift-risk -f horizon_days=7 -f create_issue=false
```

Show the MySQL service container, the report in the run **Summary** (job summary), the
`schedule-risk-report` artifact, and the `notify` job consuming the `findings` output of the report
job. Mention the cron line: it runs every night at 03:00 UTC without anyone touching it.

### 3.5 The OS × JDK matrix (2 min)

```bash
gh workflow run demo-matrix
```

Show four cells running at the same time (Ubuntu/21, Ubuntu/25, Windows/21, Windows/25), the
OS-specific steps (`runner.os == 'Windows'`), that JDK 25 cells are allowed to fail
(`continue-on-error`), and the `fan-in` job that downloads the reports of all four cells.

### 3.6 API-triggered run (2 min)

```bash
curl -X POST \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/repos/microservices-happens/shift-happens/dispatches \
  -d '{"event_type":"shift-happens-demo","client_payload":{"service":"scheduling-service","reason":"live demo"}}'
```

Show that the `setup` job printed the raw `client_payload`, turned it into `service`/`directory`
outputs, validated it, and that the run then called the same reusable slice pipeline.
`repository_dispatch` needs a token with `actions:write` (fine-grained) or `repo` (classic), and the
workflow file must exist on the default branch.

### 3.7 The whole gate on demand, including the image build and scan (3 min)

The PR gate is the only pipeline that builds and scans images, so it can be triggered without a pull
request:

```bash
gh workflow run services -f services=all                       # every service
gh workflow run services -f services="identity-service"        # one slice
gh workflow run services -f services=auto                      # detect from the last commit
```

Show the job graph `changes -> unit-tests / integration-tests -> images -> ci`, the parallel matrix
rows, the Trivy table in the `image (...)` job log, and the `ci` job that turns any red row into a
red gate. This is the pipeline to show when someone asks "does the image actually build?".


## 4. Data sharing summary

| Mechanism | Where | What travels |
|---|---|---|
| `actions/cache` | every slice pipeline, matrix demo | Maven repository, keyed by OS + JDK + the service's `pom.xml` |
| `upload-artifact` / `download-artifact` | `_service-ci.yml` (jar, reports), `system-tests.yml` (Docker images), `demo-matrix.yml` (per-cell reports), `nightly-shift-risk.yml` (report) | Build output between jobs and runs |
| Job `outputs` | `system-tests.yml` (matrix), `nightly-shift-risk.yml` (findings), `on-demand-demo.yml` (service) | Small values between jobs |
| `$GITHUB_STEP_SUMMARY` | every pipeline | Human-readable result on the run page |
| GHCR image push | `services.yml` on `main` | Deployable images, tagged with the commit SHA |
| `workflow_run` | `system-tests.yml` | "When the service gate passed on main, run the cross-service tests" |

## 5. Known limitations (be honest about them in the demo)

* Per-service workflows run on **branch pushes**, not on PRs, so a PR is not checked twice. The PR
  gate is `services.yml`.
* A path-filtered workflow that does not run reports no check at all; that is why branch protection
  should require `services / ci` and treat the rest as gates that appear when they are relevant.
* `system-tests.yml` builds the images it needs on every run. That is fine for nightly/manual runs;
  on every PR it would pull the images `services.yml` already pushed to GHCR instead.
* The nightly report reads the monolith seed data. It is deliberately a script (not logic inside the
  YAML) so swapping the data source for the Scheduling/Leave read models later does not change the
  pipeline.
* The image scan fails on fixable HIGH/CRITICAL findings; while the services are skeletons, the base
  images are the only thing being scanned.

## 6. Troubleshooting

### `status code: 403, reason phrase: Forbidden` from Maven Central

Symptom: a job fails while resolving the parent POM, for example on `windows-latest / JDK 21`:

```
[FATAL] Non-resolvable parent POM for dk.ek.shift_happens:identity-service:0.1.0-SNAPSHOT:
  Could not transfer artifact org.springframework.boot:spring-boot-starter-parent:pom:3.5.10
  from/to central (https://repo.maven.apache.org/maven2): status code: 403, reason phrase: Forbidden
```

The 403 comes from Maven Central's CDN, not from the POM: many jobs (we start up to 28 at once)
download the same artifacts from a shared runner IP. Every Maven step in every workflow therefore
goes through `.github/actions/maven-build`, which:

1. runs Maven once against the default Central host;
2. if the failure looks like a transfer problem, retries up to three times with `-U`, deletes the
   `*.lastUpdated` markers a failed transfer leaves behind (they make Maven treat the artifact as
   "absent" until the update interval has elapsed) and switches to the alternate Central hostname
   through `.github/maven/settings-fallback.xml`;
3. does **not** retry a compile or test failure, so a real break stays visible instead of being
   hidden behind three attempts.

The Maven cache key is also shared per OS + JDK (`m2-<os>-jdk<java>-v1`) instead of per service, so
only one job per OS/JDK downloads Spring Boot and the rest restore it. Bump the `-v1` suffix to
invalidate every Maven cache on purpose.

If it still fails after three attempts, re-run just the failed jobs:
`gh run rerun <run-id> --failed`.

### `Unable to resolve action ... unable to find version 0.28.0`

Symptom: every `image (...)` job fails before running a single step:

```
Unable to resolve action `aquasecurity/trivy-action@0.28.0`, unable to find version `0.28.0`
```

The tags of `aquasecurity/trivy-action` carry a `v` prefix (`v0.28.0`, `v0.33.1`, `v0.36.0`), so
`0.28.0` never resolves. The scan is now pinned to `aquasecurity/trivy-action@v0.36.0`.

Rule for every action in this repo: check the tag on the repository (or with
`curl https://api.github.com/repos/<owner>/<repo>/tags`) before pinning, and prefer the `vX` or
`vX.Y.Z` form the project publishes. All references currently used are verified to resolve:
`actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`, `actions/cache@v4`,
`actions/upload-artifact@v4`, `actions/download-artifact@v4`,
`actions/dependency-review-action@v4`, `dorny/paths-filter@v3`, `docker/setup-buildx-action@v3`,
`docker/build-push-action@v6`, `github/codeql-action@v3`, `aquasecurity/trivy-action@v0.36.0`.

### The image scan is report-only by default

`aquasecurity/trivy-action` is given `exit-code: ${{ vars.TRIVY_EXIT_CODE || '0' }}`. While the
images are still walking skeletons, the only thing being scanned is the `eclipse-temurin` base
image, and failing the pipeline on base-image CVEs would make the demo red for no reason. The scan
still runs and prints its table in the `image (...)` job log. To make it a blocking gate
(P6.7): repository **Settings → Secrets and variables → Actions → Variables → New variable**,
name `TRIVY_EXIT_CODE`, value `1`.

### The Dockerfile build retries too

`services/<name>/Dockerfile` runs Maven twice (`dependency:go-offline`, then `package`). Each of
those lines is an `||` chain of three attempts, and the retries add `-U`, so a transient Central
failure inside a Docker build does not fail the image either. If all three attempts fail, the build
fails as before.

### A red `windows-latest / JDK 25` cell

JDK 25 cells in `demo-matrix.yml` are marked `experimental` and use `continue-on-error`, so they may
go red without failing the run. JDK 21 is the version the services ship with and must stay green.



