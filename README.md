# Shift Happens

Shift scheduling and leave management, built as seven Spring Boot microservices plus a serverless
email function and a React frontend (Development of Large Systems final project). Browsers talk REST
and GraphQL through one gateway; services talk to each other only through RabbitMQ events. Each
service owns its own database.

This README is the **installation procedure**: from `git clone` to a running stack with Docker
Compose (development) and on a local Kubernetes cluster (production-like). The design is in
[docs/architecture.md](docs/architecture.md).

> The original monolith (databases course) is still in the repository as reference only. Its
> documentation has moved to [docs/old/monolith-README.md](docs/old/monolith-README.md); see
> [Legacy: the monolith](#legacy-the-monolith) at the end. Nothing below uses it.

## Contents

1. [Repository layout](#repository-layout)
2. [Prerequisites](#prerequisites)
3. [Clone](#1-clone)
4. [Run with Docker Compose](#2-run-with-docker-compose)
5. [Run on local Kubernetes](#3-run-on-local-kubernetes)
6. [Run the tests](#4-run-the-tests)
7. [Frontend development](#5-frontend-development)
8. [Troubleshooting](#troubleshooting)
9. [Further documentation](#further-documentation)
10. [Legacy: the monolith](#legacy-the-monolith)

## Repository layout

```
.
├── contracts/                         # Source of truth for every API: openapi/, graphql/, asyncapi.yaml, events/
├── services/<name>-service/           # One Spring Boot service each: pom.xml, Dockerfile, README.md, src/
│                                      #   identity, workforce, scheduling, leave, ai, notification, audit
├── functions/email-function/          # Serverless email sender (RabbitMQ queue email.send)
├── frontend/                          # React + Vite SPA; Dockerfile.microservices builds the nginx image
├── tests/cooperation/                 # System tests: several real service containers + RabbitMQ
├── docker-compose.microservices.yml   # Development environment (this README, section 2)
├── docker/microservices/              # Config mounted by that Compose file (OTel collector, Keycloak realm)
├── kubernetes/                        # Plain manifests for local Kubernetes (this README, section 3)
├── k8s/                               # Older Kustomize scaffold (legacy, see section 3)
├── scripts/                           # seed-dev.sh (demo data), docker-env.sh (Testcontainers + Colima)
├── docs/                              # architecture.md, project-plan.md, ci-demo.md, adr/
└── .github/workflows/                 # ci.yml, system-tests.yml, codeql.yml, nightly-shift-risk.yml
```

Monolith files (reference only, not used here): `src/`, the root `pom.xml`, `Dockerfile`,
`docker-compose_old.yml`, `docker-compose.test_old.yml`, `Makefile.old`, `scripts_old/`, `docs/old/`.

Databases need no manual initialisation: each service creates its own schema with Flyway when it
starts, and the MongoDB read models (scheduling-query, leave-query) are filled from RabbitMQ events.
Demo data is optional and loaded with `scripts/seed-dev.sh` (section 2.5).

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Git | any recent | cloning |
| Docker Engine with BuildKit, plus **Docker Compose v2.17 or newer** (`docker compose`, not `docker-compose`) | Docker Desktop, or Colima on macOS | Compose stack, image builds, integration tests. Compose v2.17+ is required because the service builds use `additional_contexts` |
| Bash and `curl` | any | `scripts/seed-dev.sh`, `kubernetes/secrets/create-secrets.sh` (on Windows: Git Bash or WSL) |
| OpenSSL | any | generating secrets (`openssl rand`, `openssl genpkey`) |
| Java (JDK) | **21** | running the Maven tests outside Docker. Maven itself comes with the wrapper `./mvnw` (Maven 3.9.12) |
| Node.js + npm | **22** (the version CI and `frontend/Dockerfile.microservices` use) | frontend lint/typecheck/build and the Vite dev server only; the Compose and Kubernetes images build the frontend themselves |
| `kubectl` + a local single-node cluster | a current release | section 3 only. Docker Desktop's built-in Kubernetes, or k3s (for example `colima start --kubernetes`) |

Check:

```bash
git --version
docker version
docker compose version      # must print v2.17.0 or newer
java -version               # 21
node --version              # v22.x
kubectl version --client    # section 3 only
```

You only need Docker (and Bash/OpenSSL) to run the system with Compose. Java and Node are only
needed for running tests and frontend tooling on the host.

## 1. Clone

```bash
git clone https://github.com/microservices-happens/shift-happens.git
cd shift-happens
```

Every command below is run from this repository root.

## 2. Run with Docker Compose

Compose builds every image from your local source, so it runs exactly the code you have checked out.
The file is `docker-compose.microservices.yml`; always pass it with `-f`.

### 2.1 Create `.env`

Compose reads `.env` in the repository root for variable substitution. Every variable has a
working default in the Compose file, so `.env` only overrides.

```bash
cp .env.example .env
```

`.env` is git-ignored; never commit it. Then edit `.env`:

1. **Password pepper.** `PASSWORD_PEPPER` is required by identity-service (it refuses to start
   without it). `.env.example` ships a value; for anything other than a throwaway local stack,
   replace it with a random one, for example the output of:

   ```bash
   openssl rand -hex 24
   ```

   Changing the pepper later makes every stored password hash invalid (start over with
   `down -v`, section 2.7).

2. **First administrator (needed to log in at all).** No account has a password until you set
   one. Set both of these (both empty = off; only one set = identity-service does not start; the
   password needs at least 8 characters):

   ```dotenv
   BOOTSTRAP_ADMIN_EMAIL=admin@shift-happens.example
   BOOTSTRAP_ADMIN_PASSWORD=<choose a password, min. 8 characters>
   ```

   `admin@shift-happens.example` is the Administrator in the demo data (section 2.5). When that
   employee arrives in identity-service from workforce-service and has no password yet, it gets this
   password. An existing password is never overwritten.

3. **Optional: passwords for the other demo accounts.** Set `SEED_DEMO_PASSWORD=<min. 8 characters>`
   and `scripts/seed-dev.sh` gives that password to every other active demo account. Leave it empty
   to log in as the admin only.

4. **Leave `JWT_PRIVATE_KEY_PATH` empty.** Compose mounts no key file, so a path there makes
   identity-service fail to start. With it empty, identity-service generates an RS256 signing key at
   startup; tokens become invalid when identity-service restarts, so just log in again. (Kubernetes
   uses a persistent key, section 3.2.)

5. **Optional: Google/GitHub login.** Leave the `OIDC_*` variables empty and those buttons are
   disabled (local password login works without them). To enable one, register an OAuth client with
   the callback URL `https://localhost:8443/api/v1/auth/oidc/google/callback` (or `.../github/callback`)
   and put its id and secret in `OIDC_GOOGLE_CLIENT_ID`/`OIDC_GOOGLE_CLIENT_SECRET` or
   `OIDC_GITHUB_CLIENT_ID`/`OIDC_GITHUB_CLIENT_SECRET`. Step-by-step provider setup:
   [services/identity-service/README.md](services/identity-service/README.md#implemented-so-far-googlegithub-login-p23).

   **Local OIDC demo (no account needed):** Compose also runs Keycloak (`local-oidc`, realm
   `shift-happens`) and sets `OIDC_LOCAL_CLIENT_ID`, so the **Local demo (Keycloak)** button works out
   of the box. After `scripts/seed-dev.sh`, sign in with
   `admin@shift-happens.example`, `sofie.jensen@shift-happens.example` or
   `ida.hansen@shift-happens.example` and the password `Demo1234!`. It is a real OpenID Connect
   provider (authorization code + PKCE S256, ID-token signature, issuer and audience checks); Google and
   GitHub use the same code path.

Database, RabbitMQ and SMTP passwords are not in `.env.example`; Compose falls back to its
development defaults (`identity_dev`, `shift_dev`, ...). To override one, add it to `.env` using the
name in `docker-compose.microservices.yml` (for example `RABBITMQ_PASSWORD`, `IDENTITY_DB_PASSWORD`)
before the first start; databases only read their password when their volume is created.

The upper part of `.env.example` (`DB_URL`, `MYSQL_*`, `NEO4J_*`, `JWT_SECRET`, ...) belongs to the
legacy monolith and is ignored by the microservices.

### 2.2 Start infrastructure only (optional)

Databases, RabbitMQ, Mailpit and the OpenTelemetry collector, without any application container.
Useful when you run a service from your IDE.

```bash
docker compose -f docker-compose.microservices.yml up -d
```

### 2.3 Start the full stack

```bash
docker compose -f docker-compose.microservices.yml --profile app up -d --build
```

The `app` profile adds the gateway, the frontend, the seven services (scheduling and leave each run
twice: `*-command` and `*-query`) and email-function. The first build downloads Maven and npm
dependencies and takes several minutes.

To also run the local LLM for ai-service, add the `ai` profile (the first start downloads the model
`llama3.2:1b` through the one-shot `ollama-pull` container):

```bash
docker compose -f docker-compose.microservices.yml --profile app --profile ai up -d --build
```

Without `--profile ai`, ai-service still runs and ranks replacement candidates by a simple rule
(suggestion source `rule`).

### 2.4 Check that it is up

```bash
docker compose -f docker-compose.microservices.yml --profile app ps
```

Wait until every service with a healthcheck shows `(healthy)`. The Java services' healthchecks call
`GET /health` on port 8080 inside the container. A service can restart a few times while its
database and RabbitMQ start; that is expected. email-function has no healthcheck; in Compose it is a
long-running worker (`EMAIL_FUNCTION_EXIT_WHEN_IDLE=false`), while the Kubernetes SaledJob sets that flag
to true so each Job drains `email.send` and exits.

The services publish no host ports; everything goes through the gateway. Check one service's
health directly with:

```bash
docker compose -f docker-compose.microservices.yml --profile app exec identity-service curl -fsS http://localhost:8080/health
```

Ports published on `127.0.0.1`:

| What | URL |
|---|---|
| Gateway: frontend and `/api/*` | https://localhost:8443 (Caddy `tls internal`, a self-signed certificate: accept the browser warning, use `curl -k`) |
| Keycloak (local OIDC provider, see 2.5) | http://localhost:8090 (admin console: `admin` / `admin`; demo users use `Demo1234!`) |
| RabbitMQ management UI | http://localhost:15672 (user `shift`, password `shift_dev` unless you overrode `RABBITMQ_USER`/`RABBITMQ_PASSWORD`) |
| Mailpit (captured emails) | http://localhost:8025 |
| OpenTelemetry collector (OTLP gRPC / HTTP) | `localhost:4317` / `localhost:4318` |

Through the gateway:

```bash
curl -k https://localhost:8443/health                 # "ok" from the frontend container
curl -k -i https://localhost:8443/api/v1/employees    # 401: the API needs a JWT
```

### 2.5 Load demo data and log in the first time

A fresh stack has no employees, so no one can log in yet. Once the services are healthy (their
RabbitMQ queues must exist first), load the demo data:

```bash
scripts/seed-dev.sh
```

The script refuses to run until workforce-db is migrated and the queue `identity.accounts` exists,
and warns if a scheduling or leave queue is missing. It writes 13 made-up employees with departments,
job roles, work locations and contracts into workforce-db; workforce-service publishes them as
events, and identity, scheduling and leave build their own copies. Running it again is a no-op.
Details and the list of demo employees:
[services/workforce-service/README.md](services/workforce-service/README.md#local-demo-data-p19).

Log in at **https://localhost:8443/login** with `BOOTSTRAP_ADMIN_EMAIL` /
`BOOTSTRAP_ADMIN_PASSWORD` from your `.env`. If you set `SEED_DEMO_PASSWORD`, the managers and
employees in the demo data log in with that password.

The same login from the command line (replace the placeholders):

```bash
curl -k -X POST https://localhost:8443/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@shift-happens.example","password":"<BOOTSTRAP_ADMIN_PASSWORD>"}'
```

The response contains a `token`; send it as `Authorization: Bearer <token>`. Every service validates
it against identity-service's public key (`JWT_JWKS_URL`).

If you start without demo data, the bootstrap admin only works once an Administrator with that email
exists in workforce, and creating one through the API already needs a token. So use the demo data.

Swagger UI (`/swagger-ui.html`) and GraphiQL are served on each service's own port 8080 and are not
routed through the gateway; Compose does not publish those ports. On Kubernetes, reach them with
`kubectl port-forward` (section 3.4).

### 2.6 Logs

```bash
docker compose -f docker-compose.microservices.yml --profile app logs -f identity-service
```

### 2.7 Stop and clean up

```bash
# stop and remove the containers, keep the data
docker compose -f docker-compose.microservices.yml --profile app --profile ai down

# also delete all volumes (databases, RabbitMQ, Ollama model, gateway certificate): a clean start
docker compose -f docker-compose.microservices.yml --profile app --profile ai down -v
```

Pass the same profiles you started with (passing `--profile ai` when you did not use it is harmless),
otherwise the containers of those profiles are not removed.

## 3. Run on local Kubernetes

The manifests are in `kubernetes/` (one folder per Compose service, same service names) and are
documented in detail in [kubernetes/README.md](kubernetes/README.md). The cluster does **not** build
from your source: it pulls the images CI publishes on every merge to `main`,
`ghcr.io/microservices-happens/<service>:main` (public, amd64 + arm64, `imagePullPolicy: Always`).
Unmerged local changes are not in the cluster; use Compose for those.

`k8s/` is an older Kustomize scaffold, legacy until issue #64 decides between the two folders; it is
not used here.

What the manifests assume:

- **A single-node cluster.** Each database and RabbitMQ has a `hostPath` PersistentVolume
  (`storageClassName: manual`, `persistentVolumeReclaimPolicy: Retain`) at
  `/var/lib/shift-happens/<name>` on the node (`type: DirectoryOrCreate`).
- **NodePorts** `30443` (api-gateway, HTTPS), `31672` (RabbitMQ management UI, Service
  `rabbitmq-management`) and `30025` (Mailpit UI, Service `mailpit-ui`). Everything else is ClusterIP.
- The default namespace of the current kubectl context (the manifests set no namespace).

The commands are the same on any cluster; only how you reach the NodePorts differs (section 3.4).

### 3.1 Select the cluster

Docker Desktop: Settings → Kubernetes → Enable Kubernetes, then:

```bash
kubectl config use-context docker-desktop
```

k3s with Colima (macOS): `colima start --kubernetes`, then `kubectl config use-context colima`.

On either:

```bash
kubectl config current-context
kubectl get nodes            # one node, STATUS Ready
```

### 3.2 Create the Secrets

No secret is committed. Each service gets its own Secret (for example `identity-service-secrets`),
created by a script from two git-ignored files in `kubernetes/secrets/`. First generate the files
without touching the cluster:

```bash
kubernetes/secrets/create-secrets.sh --generate --dry-run
```

This writes `kubernetes/secrets/secrets.env` (from `secrets.env.example`, every `CHANGE_ME` replaced
by a random value) and `kubernetes/secrets/jwt-private-key.pem` (the identity-service RS256 signing
key, RSA 2048, PKCS#8), then lists the Secret names and keys. Existing files are kept.

Edit `kubernetes/secrets/secrets.env` and set the first administrator (same rules as in
section 2.1):

```dotenv
BOOTSTRAP_ADMIN_EMAIL=admin@shift-happens.example
BOOTSTRAP_ADMIN_PASSWORD=<choose a password, min. 8 characters>
```

Optionally add `OIDC_GOOGLE_CLIENT_SECRET`/`OIDC_GITHUB_CLIENT_SECRET` and `SMTP_USERNAME`/`SMTP_PASSWORD`.
Then create the Secrets in the current context:

```bash
kubernetes/secrets/create-secrets.sh
```

Re-run it after every edit of `secrets.env`, then restart the affected Deployment, for example
`kubectl rollout restart deployment/identity-service`. The RabbitMQ management password is
`RABBITMQ_PASSWORD` in `secrets.env`.

### 3.3 Deploy

```bash
kubectl apply -R -f kubernetes/
kubectl get pods -w          # wait until every pod is Running and READY 1/1; Ctrl+C to stop watching
```

The first start pulls all images and can take several minutes. App pods may restart a few times
until their database and RabbitMQ are ready; that is expected. Known exception: `email-function`
exits when its queue is empty, so it cycles through `Completed` and `CrashLoopBackOff`; that is
expected on these manifests (kubernetes/README.md, "Known issues"). A pod in
`CreateContainerConfigError` is missing its Secret: run section 3.2.

Optional, for LLM-ranked suggestions (otherwise ai-service uses its rule-based fallback):

```bash
kubectl rollout status deployment/ollama
kubectl exec deployment/ollama -- ollama pull llama3.2:1b
```

### 3.4 Check that it is up and open the app

```bash
kubectl get pods,svc
kubectl get pv,pvc           # every PVC Bound
```

The readiness probes call `GET /health` on each service, so `READY 1/1` means `/health` answers.

**Docker Desktop** forwards NodePorts to localhost:

| What | URL |
|---|---|
| Gateway: frontend and `/api/*` | https://localhost:30443 (self-signed: accept the warning, `curl -k`) |
| RabbitMQ management UI | http://localhost:31672 (user `shift`) |
| Mailpit UI | http://localhost:30025 |

**Other clusters (for example k3s on Colima):** the NodePorts are on the node's address
(`kubectl get nodes -o wide`, column INTERNAL-IP), for example `https://<node-ip>:30443`. Or use a
port-forward, which works on every cluster:

```bash
kubectl port-forward svc/api-gateway 30443:443    # then https://localhost:30443; Ctrl+C to stop
```

```bash
curl -k https://localhost:30443/health                 # "ok" from the frontend
curl -k -i https://localhost:30443/api/v1/employees    # 401 without a token
```

Swagger UI of one service, through a port-forward (any free local port):

```bash
kubectl port-forward svc/workforce-service 8081:8080   # then http://localhost:8081/swagger-ui.html
```

### 3.5 Demo data and first login

`scripts/seed-dev.sh` only works against Compose. On Kubernetes, load the same SQL file into
workforce-db once all pods are ready (identity-service must be running, so its queue exists):

```bash
kubectl exec -i deployment/workforce-db -- \
  sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < services/workforce-service/seed/dev-seed.sql
```

This is the psql call `scripts/seed-dev.sh` makes, against the Kubernetes Deployment instead of the
Compose container. It only loads the employees; it does not set `SEED_DEMO_PASSWORD` on the other demo
accounts. Then log in at `https://localhost:30443/login` (or your node address) with the
`BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD` from `kubernetes/secrets/secrets.env`. The admin
can set other accounts' passwords with `PUT /api/v1/auth/accounts/{employeeId}/password`.

Google/GitHub login does not work on this setup yet: the callback URLs in
`kubernetes/identity-service/deployment.yaml` say `https://localhost:8443`, while the gateway is on
`30443` (kubernetes/README.md, "Known issues"). Local password login is unaffected.

### 3.6 Logs, updates and teardown

```bash
kubectl logs deployment/identity-service -f
kubectl describe pod -l app=identity-service      # events: pull errors, probe failures

# pick up a newer :main image after CI has pushed it
kubectl rollout restart deployment/identity-service

# remove everything the manifests created (Secrets stay; data stays on the node)
kubectl delete -R -f kubernetes/
```

The PersistentVolumes use `Retain` and hostPath, so database data survives `kubectl delete`. For empty
databases, delete `/var/lib/shift-happens/` on the node or reset the cluster (Docker Desktop:
Settings → Kubernetes → Reset Kubernetes Cluster). The Secrets are not in the manifests; remove them
with `kubectl delete secret <name>` (names: `kubernetes/secrets/create-secrets.sh --dry-run`). If you
keep the data, keep `secrets.env` too: databases keep the password they were created with, and a new
`PASSWORD_PEPPER` invalidates stored password hashes.

## 4. Run the tests

Integration tests (`*IT`) and cooperation tests start real PostgreSQL, MongoDB and RabbitMQ through
Testcontainers, so Docker must be running. With Docker Desktop that works as is. With **Colima** (or
another Docker context), run this once per shell first:

```bash
source scripts/docker-env.sh
```

| What | Command (from the repository root) |
|---|---|
| Unit tests, one service | `./mvnw -B -f services/<service>/pom.xml test` |
| Unit + integration tests, one service | `./mvnw -B -f services/<service>/pom.xml verify` |
| email-function | `./mvnw -B -f functions/email-function/pom.xml verify` |
| Frontend lint, typecheck, build | `npm --prefix frontend ci && npm --prefix frontend run lint && npm --prefix frontend run typecheck && npm --prefix frontend run build` |

`<service>` is a folder name in `services/`: `identity-service`, `workforce-service`,
`scheduling-service`, `leave-service`, `ai-service`, `notification-service`, `audit-service`.

Cooperation tests run real service images, which you build first:

```bash
docker build --build-context contracts=contracts -t shift-happens/identity-service:ci services/identity-service
docker build --build-context contracts=contracts -t shift-happens/workforce-service:ci services/workforce-service
docker build --build-context contracts=contracts -t shift-happens/leave-service:ci services/leave-service
docker build --build-context contracts=contracts -t shift-happens/scheduling-service:ci services/scheduling-service
docker build -t shift-happens/ai-service:ci services/ai-service
./mvnw -B -f tests/cooperation/pom.xml verify
```

`--build-context contracts=contracts` is required for every service whose Dockerfile copies its
OpenAPI contract for Swagger UI (all services except ai-service); Compose passes it through
`additional_contexts`. A test whose image is missing is skipped, not failed (set
`COOPERATION_REQUIRE_IMAGES=true` to make it fail). See
[tests/cooperation/README.md](tests/cooperation/README.md).

CI runs all of this on every pull request; see [docs/ci-demo.md](docs/ci-demo.md).

## 5. Frontend development

The images build the frontend themselves; this is only for working on it with hot reload.

```bash
npm --prefix frontend ci
npm --prefix frontend run dev        # http://localhost:5173
```

The Vite dev proxy (`frontend/vite.config.ts`) forwards `/api` unchanged to the gateway at
`https://localhost:8443`, so the dev server works against a running Compose stack (`--profile app`).
Set `VITE_DEV_PROXY_TARGET` (see `frontend/.env.example`) to point it somewhere else.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `additional_contexts` / `build-context` error during `--build` | Compose older than v2.17. Update Docker Desktop or the Compose plugin |
| identity-service exits right after start | `PASSWORD_PEPPER` empty, only one of `BOOTSTRAP_ADMIN_EMAIL`/`BOOTSTRAP_ADMIN_PASSWORD` set, a bootstrap password under 8 characters, or `JWT_PRIVATE_KEY_PATH` set under Compose. `docker compose -f docker-compose.microservices.yml --profile app logs identity-service` |
| Login gives `401` for the admin | Demo data not loaded (section 2.5 / 3.5), or `BOOTSTRAP_ADMIN_EMAIL` is not `admin@shift-happens.example`, or the bootstrap was set after the account already got another password |
| All API calls `401` after a restart (Compose) | identity-service generated a new signing key; log in again |
| `scripts/seed-dev.sh`: "queue identity.accounts does not exist" | identity-service has not started yet; wait for `(healthy)` and run it again |
| Database authentication errors after changing a password | The volume keeps the old password: `down -v` (Compose) or wipe the hostPath data (Kubernetes) |
| Testcontainers cannot find Docker (Colima) | `source scripts/docker-env.sh` in the same shell |
| Kubernetes pod `CreateContainerConfigError` | Its Secret is missing: `kubernetes/secrets/create-secrets.sh` |
| Kubernetes pod `ImagePullBackOff` | No network to `ghcr.io`/Docker Hub, or the Docker Hub anonymous pull limit; see kubernetes/README.md |

More: [kubernetes/README.md](kubernetes/README.md) (logs, image updates, known differences from
Compose) and [docs/ci-demo.md](docs/ci-demo.md) (CI).

## Further documentation

- [docs/architecture.md](docs/architecture.md): requirements, service decomposition, patterns,
  deployment, CI/CD, testing, monitoring, versioning.
- [contracts/README.md](contracts/README.md): REST (OpenAPI), GraphQL and event (AsyncAPI) contracts.
- `services/<service>/README.md`, `functions/email-function/README.md`: what each part owns, provides,
  consumes and publishes.
- [docs/project-plan.md](docs/project-plan.md): work packages and task IDs (P0–P9).
- [docs/adr/](docs/adr/): architecture decision records.
- [docs/ci-demo.md](docs/ci-demo.md): the CI workflows and how to run them.
- [docs/manual-testing.md](docs/manual-testing.md): logins, URLs and copy-paste API calls for trying the
  running stack by hand.

## Legacy: the monolith

The original single Spring Boot application with MySQL, MongoDB and Neo4j (databases course) is kept
in `src/`, the root `pom.xml`, `docker-compose_old.yml` and the other `*_old*` files as reference only.
It is not part of the system above and is not maintained. Its previous README, including its own
installation steps, is in [docs/old/monolith-README.md](docs/old/monolith-README.md).
