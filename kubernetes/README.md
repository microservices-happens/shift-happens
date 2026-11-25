# Shift Happens on local Kubernetes

Plain manifests for Docker Desktop Kubernetes (kubeadm), translated from
`docker-compose.microservices.yml` (the `app` profile, with Ollama deployed alongside the AI service).
One folder per compose service; each Kubernetes Service has the same name as
its compose service, so hostnames like `DB_HOST=identity-db` work unchanged.

```
kubernetes/
  shared/            ConfigMap service-env (x-service-env); no secrets
  secrets/           create-secrets.sh + secrets.env.example (Secrets are created from local files, never committed)
  <service>/         deployment.yaml, service.yaml
  <database>/        + pv.yaml (hostPath PV, storageClassName manual, Retain + PVC)
  api-gateway/       + configmap.yaml (Caddyfile copied from compose)
  otel-collector/    + configmap.yaml (Kubernetes OTLP pipelines; copy of docker/microservices/otel-collector.yml)
  tempo/, loki/, prometheus/, grafana/  local observability stack
  autoscaling/       hpa.yaml (CPU) + keda.yaml (queue-based ScaledObject/ScaledJob) + optional metrics bridge example
  metrics-server/    install note for the metrics API used by the HPAs
  observability/     kube-state-metrics (Deployment replica metrics for the Grafana autoscaling panel)
  infrastructure/    cluster add-ons (ingress-nginx, KEDA, metrics-server)
  namespace.yaml     the shift-happens namespace
  ingress.yaml       ingress-nginx rules, same paths as the Compose Caddyfile
```

Our images are `ghcr.io/microservices-happens/<service>:main`, built and pushed by CI on every
merge to `main` (multi-platform: linux/amd64 + linux/arm64), pulled with `imagePullPolicy: Always`.

## Prerequisites

```bash
kubectl config use-context docker-desktop
kubectl get nodes            # one node, Ready
```

Cluster add-ons used by autoscaling (install once):

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.1/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json --patch '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
# KEDA: see autoscaling/README.md
# ingress-nginx: see infrastructure/README.md
```
> On Windows PowerShell the kubectl commands are single-line. PowerShell does not use a backslash as a line continuation; use the one-line --patch form shown above.

## Secrets (once per cluster, before the first deploy)

No secret value is committed. Each service has its own Secret holding only what it needs; the
service's database Deployment reads its password from the same Secret. They are created from two
git-ignored files in `kubernetes/secrets/`:

```bash
kubectl apply -f kubernetes/namespace.yaml
NAMESPACE=shift-happens bash kubernetes/secrets/create-secrets.sh --generate
```
On Windows PowerShell, run the shell script through Git Bash (Git for Windows includes `bash` and `openssl`):

```powershell
$env:NAMESPACE='shift-happens'
& 'C:\Program Files\Git\bin\bash.exe' kubernetes/secrets/create-secrets.sh --generate
```

`--generate` writes `kubernetes/secrets/secrets.env` (from `secrets.env.example`, every `CHANGE_ME`
replaced by a random value) and `kubernetes/secrets/jwt-private-key.pem` (RSA 2048, PKCS#8), unless
they already exist, and then creates or updates the Secrets in the current kubectl context
(`NAMESPACE=<ns>` to pick another namespace). Edit `secrets.env` to add OIDC client secrets, SMTP
credentials or the bootstrap admin, then run the script again without `--generate` and restart the
affected Deployment (`kubectl rollout restart deployment/identity-service`). `--dry-run` checks the
files and prints secret names and keys without touching the cluster.

| Secret | Keys | Used by |
|---|---|---|
| `rabbitmq-secrets` | `RABBITMQ_DEFAULT_PASS` | rabbitmq |
| `identity-service-secrets` | `RABBITMQ_PASSWORD`, `DB_PASSWORD`, `PASSWORD_PEPPER`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `OIDC_GOOGLE_CLIENT_SECRET`, `OIDC_GITHUB_CLIENT_SECRET`, `jwt-private-key.pem` (mounted file, `JWT_PRIVATE_KEY_PATH`) | identity-service, identity-db |
| `workforce-service-secrets` | `RABBITMQ_PASSWORD`, `DB_PASSWORD` | workforce-service, workforce-db |
| `scheduling-command-secrets` | `RABBITMQ_PASSWORD`, `DB_PASSWORD` | scheduling-command, scheduling-write-db |
| `scheduling-query-secrets` | `RABBITMQ_PASSWORD`, `MONGO_PASSWORD` | scheduling-query, scheduling-read-db |
| `leave-command-secrets` | `RABBITMQ_PASSWORD`, `DB_PASSWORD` | leave-command, leave-write-db |
| `leave-query-secrets` | `RABBITMQ_PASSWORD`, `MONGO_PASSWORD` | leave-query, leave-read-db |
| `notification-service-secrets` | `RABBITMQ_PASSWORD`, `DB_PASSWORD` | notification-service, notification-db |
| `audit-service-secrets` | `RABBITMQ_PASSWORD`, `DB_PASSWORD` | audit-service, audit-db |
| `ai-service-secrets` | `RABBITMQ_PASSWORD`, `DB_PASSWORD` | ai-service, ai-db |
| `email-function-secrets` | `RABBITMQ_PASSWORD`, `SMTP_USERNAME`, `SMTP_PASSWORD` | email-function |
| `grafana-secrets` | `GRAFANA_ADMIN_PASSWORD` | Grafana |

Secret keys are the env var names from `docker-compose.microservices.yml` (databases map
`DB_PASSWORD`/`MONGO_PASSWORD` onto `POSTGRES_PASSWORD`/`MONGO_INITDB_ROOT_PASSWORD`). Usernames,
hosts and other non-sensitive settings stay as plain env or in the `service-env` ConfigMap.

**Existing cluster with data from before this change.** Postgres, Mongo and RabbitMQ only read
their password when the data directory is first initialised, and the hostPath PVs keep that data.
Either put the old compose dev passwords (`identity_dev`, `shift_dev`, ...) into `secrets.env`
before running the script, or wipe the data (see Teardown) so the new random passwords are used.
A new `PASSWORD_PEPPER` invalidates stored password hashes; reuse the old pepper to keep them. The script generates a 40-character (20-byte) pepper; do not use a longer value, because identity hashes a dummy password of 25 bytes and BCrypt rejects inputs over 72 bytes.
The old shared Secret is no longer referenced: `kubectl delete secret shift-happens-secrets`.

**Existing RabbitMQ data and the dead-letter fix.** Queues and bindings are durable and live in the hostPath
`/var/lib/shift-happens/rabbitmq`, so they survive redeploys. Older identity, scheduling and ai versions bound
their `<queue>.dlq` to `shift-happens.dlx` with `#`. The new versions remove that binding themselves on
startup and keep their queue arguments, so **no manual step is needed**: `kubectl apply -n shift-happens -R -f kubernetes/`
with the new images, and waiting messages are kept. Copies of other services' dead letters that the old
binding put into those DLQs stay until you purge them, e.g.
`kubectl exec deployment/rabbitmq -- rabbitmqctl purge_queue -p shift-happens identity.accounts.dlq` (look at
them first, see docs/wiki/Troubleshooting.md, "Messages pile up in a `.dlq`"). A queue whose *arguments*
changed (leave's `leave.command`, `PRECONDITION_FAILED` in the log) does need deleting; the commands are in
docs/wiki/Troubleshooting.md, "A service logs `PRECONDITION_FAILED` for its queue".

## Deploy

```bash
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -n shift-happens -R -f kubernetes/
kubectl get pods -w          # wait until everything is Running and 1/1 Ready
```

The optional KEDA metrics bridge is kept as `autoscaling/keda-metrics-nodeport.yaml.example`: it belongs to the `keda` namespace, so the recursive app deploy does not apply it. Apply it separately with `kubectl apply -n keda -f kubernetes/autoscaling/keda-metrics-nodeport.yaml.example` if Compose Prometheus scrapes KEDA.

App pods may restart a few times until their database and RabbitMQ are up. That is expected.
A pod stuck in `CreateContainerConfigError` means its Secret is missing: run
`kubernetes/secrets/create-secrets.sh` (see above). `kubectl apply -n shift-happens -R -f kubernetes/` only reads
`.yaml`/`.json` files, so the script and the env template in `kubernetes/secrets/` are ignored by it.



## Seed demo data

After the services are Ready, load the same demo data as Compose. The seed writes events to the outbox, so the other services build their replicas normally.

```bash
kubectl -n shift-happens exec -i deployment/workforce-db -- sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < services/workforce-service/seed/dev-seed.sql
```

PowerShell equivalent:

```powershell
Get-Content -Raw services/workforce-service/seed/dev-seed.sql | kubectl -n shift-happens exec -i deployment/workforce-db -- sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Then sign in with the `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` from `secrets.env`. The seed creates accounts but does not set passwords for the other demo users. To give them the same demo password, log in as the bootstrap admin once and set each account through the identity API:

```powershell
$login = Invoke-RestMethod -SkipCertificateCheck -Method Post -Uri 'https://localhost:30443/api/v1/auth/login' -ContentType 'application/json' -Body '{"email":"admin@shift-happens.example","password":"<bootstrap-password>"}'
$h = @{ Authorization = "Bearer $($login.token)" }
2..11 + 13 | ForEach-Object { Invoke-RestMethod -SkipCertificateCheck -Method Put -Uri "https://localhost:30443/api/v1/auth/accounts/$_/password" -Headers $h -ContentType 'application/json' -Body '{"password":"Demo1234!"}' | Out-Null }
```

## Status

```bash
kubectl -n shift-happens get pods,svc,pvc
kubectl get pv
```

## Observability stack

The Kubernetes stack runs Tempo, Loki, Prometheus and Grafana. Grafana provisions these
data sources and a dashboard for Java workload metrics. Follow [OBSERVABILITY.md](OBSERVABILITY.md)
for setup, login and smoke checks in Compose and Kubernetes.

## Logs

```bash
kubectl logs deployment/leave-command          # current logs
kubectl logs deployment/leave-command -f       # follow
kubectl logs deployment/leave-command --previous   # logs of the crashed container
kubectl describe pod -l app=leave-command      # events: pull errors, probe failures, OOM
```

## How images get updated (team workflow)

Nobody builds or pushes images by hand. The flow is:

1. Change code on a branch and open a PR. CI (`.github/workflows/ci.yml`) runs the tests and builds and scans
   the image of every changed service. Nothing is pushed from a PR.
2. Merge to `main`. CI builds the changed services (and the frontend if `frontend/` changed) for
   amd64 + arm64 and pushes them to GitHub Container Registry as
   `ghcr.io/microservices-happens/<service>:<commit-sha>` and `:main`.
3. Pick up the new image in your local cluster:

```bash
kubectl rollout restart deployment/leave-command deployment/leave-query   # leave-service backs both
```

`imagePullPolicy: Always` makes the restarted pod pull the newest `:main`.
The Actions tab shows when the `ci` run on main has finished pushing.

**Republish everything** (first setup, or after a base-image update): Actions → `ci` → Run workflow,
branch `main`, `services` = `all`.

## Rolling update demo (Grafana)

The Compose Grafana instance (`http://localhost:3001`) scrapes kube-state-metrics and Loki from the
Kubernetes stack, so it can show a Kubernetes rollout next to live application logs. The dashboards
are **Shift Happens — Kubernetes rollout & live logs** and the Compose **Shift Happens — Live
System**.

```powershell
# One new ReplicaSet, paused briefly so the overlap is visible in Prometheus.
.\scripts\kubernetes-rollout-demo.ps1 -Deployment identity-service -PauseSeconds 60

# Generate request logs in Identity and Workforce for the same dashboard.
.\scripts\live-log-demo.ps1 -Gateway https://localhost:8443 -Count 12
```

Open <http://localhost:3001/d/shift-happens-kubernetes-rollout>. Select the deployment in the
**Deployment** variable; the panel shows desired, ready, updated, unavailable and the total old +
new pod count. The script adds Grafana annotations for start, pause and resume so the screenshot
shows exactly when the rollout happened. The captured example is
[`docs/images/observability/kubernetes-rolling-update.png`](../docs/images/observability/kubernetes-rolling-update.png).

A durable update can finish between two 15-second kube-state-metrics scrapes. `-PauseSeconds` keeps
one new pod visible while the old pods keep serving, then resumes the same rollout; it does not
change the Deployment strategy. The frontend Deployment also sets `minReadySeconds: 30` so a new
pod is not marked available before it has stayed Ready.

The live log panel reads `{service=~"identity-service|workforce-service"}` from Loki. The two
services log one request line per API call (method, path, status, duration and correlation id) and
skip `/health` and `/prometheus`, so the panel stays useful without probe noise.

> The Kubernetes Grafana at `30300` is the separate in-cluster observability dashboard. The rollout
> and live-log dashboard is in Compose Grafana because that is where Prometheus and Loki scrape the
> local cluster.

## Where the images come from (read this before changing code)

- `kubectl apply` makes the cluster **pull our images from GHCR** and the public ones (postgres, mongo,
  rabbitmq, ...) from Docker Hub. Our GHCR packages are public, so pulling needs no login, and Docker picks
  the right CPU architecture (amd64 on Windows, arm64 on Apple Silicon).
- **Docker Compose does not use these images.** `docker compose -f docker-compose.microservices.yml
  --profile app up --build` still builds from your local source.
- **Your local code changes are not in the cluster** until they are merged and CI has pushed them.
  Use Compose (or run the service from your IDE) to try out unmerged changes.
- Docker Hub limits anonymous pulls to about 100 per 6 hours per IP. One full start pulls about 11 public
  images from Docker Hub. If several people on the same network hit the limit, `docker login` raises it.

### One-time setup (org admin)

1. After the first `ci` run on main has pushed (or a manual run with `services` = `all`), make each
   package public: github.com/orgs/microservices-happens/packages → package → Package settings →
   Change visibility → Public. There are 9 packages: the 7 services, `email-function` and `frontend`.
2. In each package's settings, under "Manage Actions access", check that the `shift-happens` repository
   has write access. A package that CI created is normally linked to the repo already.

## Teardown

```bash
kubectl delete -R -f kubernetes/
```

The PVs use `Retain` and hostPath on the Docker Desktop VM, so database and observability
data survives a teardown. Database data is under `/var/lib/shift-happens/<db>` and observability
data is under `/var/lib/shift-happens/observability/<service>`. To clear it, reset the Kubernetes
cluster in Docker Desktop (Settings → Kubernetes → Reset Kubernetes Cluster).

## Localhost URLs

| Service | Kubernetes type | URL |
|---|---|---|
| api-gateway (frontend + `/api/*`) | NodePort 30443 | https://localhost:30443 (self-signed: `curl -k` / accept the browser warning) |
| rabbitmq management UI | NodePort 31672 (`rabbitmq-management`) | http://localhost:31672 (user `shift`, password `RABBITMQ_PASSWORD` in `kubernetes/secrets/secrets.env`) |
| mailpit UI | NodePort 30025 (`mailpit-ui`) | http://localhost:30025 |
| Grafana | NodePort 30300 | http://localhost:30300 |
| everything else | ClusterIP | not reachable from localhost; use `kubectl port-forward svc/<name> <local>:<port>` |

## Known issues / differences from compose

- **OIDC callback URLs** (`OIDC_REDIRECT_BASE_URL`, `FRONTEND_LOGIN_CALLBACK_URL` on identity-service)
  are copied from compose and still say `https://localhost:8443`. Through the NodePort the gateway is
  on `:30443`, so Google/GitHub login redirects go to the wrong port. Local password login is unaffected.
- **Ollama** runs as a Deployment with a ClusterIP Service. The AI service uses
  `http://ollama:11434` in the same namespace. Load the default model after the Deployment is ready:

  ```sh
  kubectl rollout status deployment/ollama
  kubectl exec deployment/ollama -- ollama pull llama3.2:1b
  ```

  The model cache lives for the lifetime of the pod. Repeat the pull after pod replacement.
  AI falls back to rule-based ranking while the model is unavailable.
  See [the latency measurement](ollama/latency.md) for the report.
- **email-function** has no Service and no probe: it has no ports and no healthcheck. It runs as a
  KEDA `ScaledJob`, not a Deployment; KEDA creates a Job per burst on `email.send` and the Job exits
  when the queue is empty. Install KEDA before applying the manifests (see `autoscaling/README.md`).
- **Caddy data** (`/data`, `/config`) is an `emptyDir`, so the internal TLS certificate is regenerated
  when the gateway pod restarts.
- Compose network isolation (`internal: true` networks) is not reproduced; all pods can reach each other.
- Secrets are plain Kubernetes Secrets (base64, not encrypted at rest unless the cluster enables it).
  A real cluster would add encryption at rest or an external secret store (Sealed Secrets, External
  Secrets Operator); the per-service split and key names stay the same.
- Autoscaling: metrics-server + CPU HPA for the query roles/frontend, KEDA for the notification
  consumer and the email ScaledJob, and kube-state-metrics for the replica series in Grafana; see
  `autoscaling/README.md` and `metrics-server/README.md`. The observability stack runs in Compose
  for the demo; Prometheus scrapes KEDA (NodePort 32073) and kube-state-metrics (NodePort 32080).
