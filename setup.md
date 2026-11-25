# Run Shift Happens locally

A one-page setup for the team. Use **Docker Compose** unless you specifically need Kubernetes.

## 1. Prerequisites

- Docker Desktop (Compose v2) running
- Git Bash on Windows (for `scripts/seed-dev.sh` and `kubernetes/secrets/create-secrets.sh`)
- Java 21 and Node.js 20+ only if you want to run tests or the frontend dev server
- Optional: Python 3 + `pika` for the Kubernetes autoscaling burst
- Optional: Lens for the Kubernetes cluster

8 GB of Docker memory is a practical minimum; the full observability stack is several containers.

## 2. Start the Compose stack

From the repository root:

```powershell
docker compose -f docker-compose.microservices.yml --profile app up -d --build
```

First start takes a few minutes because every service and the frontend are built. Check progress:

```powershell
docker compose -f docker-compose.microservices.yml --profile app ps
docker compose -f docker-compose.microservices.yml --profile app logs -f identity-service scheduling-command
```

Seed the demo organisation (13 employees):

```powershell
& 'C:\Program Files\Git\bin\bash.exe' scripts/seed-dev.sh
```

On Linux/macOS use `./scripts/seed-dev.sh`.

Optional local LLM for AI suggestions:

```powershell
docker compose -f docker-compose.microservices.yml --profile app --profile ai up -d
```

Without Ollama, AI falls back to the rule-based ranking. The first `--profile ai` start pulls `llama3.2:1b`.

Smoke check:

```powershell
curl.exe -k https://localhost:8443/health
curl.exe -k -i https://localhost:8443/api/v1/employees
```

The first command returns `ok`; the second returns `401`, which is correct because the API needs a JWT.

## 3. Open the interesting parts

| What | URL | Login |
|---|---|---|
| Frontend / API gateway | <https://localhost:8443> | accept the self-signed certificate warning |
| Keycloak (local OIDC) | <http://localhost:8090> | `admin` / `admin` |
| Grafana | <http://localhost:3001> | `admin` / `admin` unless `.env` overrides it |
| Prometheus | <http://localhost:9090> | no login |
| Tempo (traces API) | <http://localhost:3200> | use Grafana Explore → Tempo |
| Loki (logs) | <http://localhost:3100> | use Grafana Explore |
| RabbitMQ management | <http://localhost:15672> | `shift` / `shift_dev` unless `.env` overrides it |
| Mailpit (captured e-mail) | <http://localhost:8025> | no login |
| cAdvisor | <http://localhost:8088> | no login |

### Demo accounts

All active seeded accounts use the password **`Demo1234!`**.

| Role | E-mail |
|---|---|
| Administrator | `admin@shift-happens.example` |
| Manager | `sofie.jensen@shift-happens.example` |
| Employee | `ida.hansen@shift-happens.example` |

### What to click

- **Administrator:** Dashboard, Employees, Departments, Leave Types, Audit Log.
- **Manager (`sofie`):** Shifts, Shift Assignments, Swap Requests, Leave Requests, understaffed/AI suggestions.
- **Employee (`ida`):** Dashboard, My Schedule, Shifts, Shift Assignments, Leave Requests.

Good first tour:

1. Log in as `admin@...` and open **Employees** and **Audit Log**.
2. Log in as `sofie@...` and open **Shifts**.
3. Log in as `ida@...` and open **My Schedule** and **Leave Requests**.

## 4. Observability stack

Open Grafana and choose **Dashboards → Shift Happens → Shift Happens — Live System**:

<http://localhost:3001/d/shift-happens-live>

Useful panels:

- request rate, p95 latency and 5xx;
- JVM heap/CPU;
- RabbitMQ ready messages and dead-letter queues;
- KEDA demand;
- Leave saga timeouts and AI fallback rate (added by the observability PR);
- **notification.events backlog vs ready pods** (KEDA/Kubernetes only).

Prometheus targets:

<http://localhost:9090/targets>

Loki logs in Grafana Explore:

```logql
{service="scheduling-command"}
```

Tempo traces in Grafana Explore: choose **Tempo**, then search for a service, for example:

```text
{ resource.service.name = "identity-service" }
```

To generate a real REST → RabbitMQ → AI trace after seeding:

```powershell
$env:TRACE_DEMO_PASSWORD = 'Demo1234!'
node scripts/observability-demo.mjs
Remove-Item Env:TRACE_DEMO_PASSWORD
```

The script prints a trace ID and a correlation ID; paste the trace ID into Grafana Explore → Tempo.
It needs the stack and `scheduling-command` + `ai-service` running.

> The notification queue-vs-pods panel is fed by the **Kubernetes** KEDA and kube-state-metrics
> NodePorts. On a Compose-only stack the panel is empty; use the Kubernetes section below for that demo.

### Performance tests with k6

The performance suite is in [`tests/performance`](tests/performance/README.md). It covers **P-01,
P-02, P-04 and P-05**; P-03 is intentionally skipped. Load the local 1,000-employee fixture once:

```powershell
Get-Content tests/performance/seed-performance.sql -Raw |
  docker compose -f docker-compose.microservices.yml exec -T workforce-db `
    psql -v ON_ERROR_STOP=1 -U workforce -d workforce
```

Prometheus is configured to receive k6 remote-write metrics:

```powershell
$env:PERF_BASE_URL = 'https://localhost:8443'
$env:K6_PROMETHEUS_RW_SERVER_URL = 'http://localhost:9090/api/v1/write'
$env:K6_PROMETHEUS_RW_TREND_STATS = 'p(95),p(99),avg,min,max'
k6 run --out experimental-prometheus-rw tests/performance/api.js
```

Open the provisioned k6 dashboard:

<http://localhost:3001/d/shift-happens-performance>

The captured local run is stored at
[`docs/images/observability/k6-performance.png`](docs/images/observability/k6-performance.png).

The baseline profile runs 50 read VUs and 10 write VUs for 60 seconds. For the 200-user P-05 run:

```powershell
$env:PERF_P05_ONLY = 'true'
k6 run --out experimental-prometheus-rw tests/performance/api.js
```

The test results are stored as `k6_*` series in Prometheus, so Grafana shows the same run instead of
a separate result file. The local stack and k6 share one workstation; the results are local evidence,
not a production SLO.

## 5. Peek at RabbitMQ

Open <http://localhost:15672> and log in with `shift` / `shift_dev` (or the values in `.env`).

- Virtual host: **`shift-happens`**
- Topic exchange: **`shift-happens.events`**
- Dead-letter exchange: **`shift-happens.dlx`**
- Important queues: `scheduling.command`, `leave.command`, `notification.events`, `email.send`
- Every queue has a matching `<queue>.dlq`

Useful checks:

```powershell
docker compose -f docker-compose.microservices.yml --profile app logs -f notification-service
docker compose -f docker-compose.microservices.yml --profile app logs -f rabbitmq
```

Click a queue to see ready/unacked messages and consumers. A non-empty `.dlq` means a consumer failed;
the payload is parked there for inspection, not silently lost.

## 6. Kubernetes (optional)

Compose and Kubernetes are separate stacks; they can run at the same time on different ports.

Quick start on Docker Desktop Kubernetes:

```powershell
kubectl config use-context docker-desktop
kubectl apply -f kubernetes/namespace.yaml
$env:NAMESPACE = 'shift-happens'
& 'C:\Program Files\Git\bin\bash.exe' kubernetes/secrets/create-secrets.sh --generate
kubectl apply -n shift-happens -R -f kubernetes/
kubectl -n shift-happens get pods
```

Wait until all pods are `Running`/`Ready`. Seed the same 13 demo employees with the command in
[`kubernetes/README.md`](kubernetes/README.md#seed-demo-data), then open:

| What | URL |
|---|---|
| Frontend / gateway | <https://localhost:30443> |
| Keycloak admin | <https://localhost:30443/admin/> (`admin` / `KEYCLOAK_ADMIN_PASSWORD` from `kubernetes/secrets/secrets.env`) |
| Grafana | <http://localhost:30300> |
| RabbitMQ management | <http://localhost:31672> |
| Mailpit | <http://localhost:30025> |

### Seeing the Kubernetes stack as one group

The Compose stack appears as one project in Docker Desktop's **Containers** view. Kubernetes pods
cannot join that group: Docker Desktop groups Compose projects by `com.docker.compose.project`, and
Kubernetes pods are created by kubelet without that label. They stay as individual `k8s_...` rows.

Use Docker Desktop's **Kubernetes** view instead and select namespace **`shift-happens`**; it lists
the pods, services and deployments of the application together. To make `kubectl` use the same
namespace by default:

```powershell
kubectl config set-context --current --namespace=shift-happens
kubectl get pods,deploy,svc
```

Lens works the same way (namespace `shift-happens`). The detailed explanation and the kind/k3d
alternative are in [`kubernetes/README.md`](kubernetes/README.md#viewing-the-whole-stack-in-docker-desktop).

The Kubernetes application uses GHCR images, not your local uncommitted code. Use Compose if you are
changing Java or frontend code.

For the autoscaling demo, install the AMQP client once and publish a burst:

```powershell
python -m pip install pika
.\scripts\notification-burst.ps1 -Count 50000
```

Then open Grafana panel **19**:

<http://localhost:3001/d/shift-happens-live?viewPanel=19>

Expected: queue rises, `notification-service` goes `1 → 3 → 4` pods, queue drains, pods return to `1`.
The detailed runbook is [`kubernetes/autoscaling/README.md`](kubernetes/autoscaling/README.md).

### Kubernetes rolling update and live logs

The rollout dashboard is in **Compose Grafana** (`3001`) because that Prometheus also scrapes the
Kubernetes kube-state-metrics endpoint. Start with the Compose observability stack running, then:

```powershell
# identity-service is prepared at 3 x v1.0.0, then rolled to 3 x v1.0.1.
.\scripts\kubernetes-rollout-demo.ps1 -Deployment identity-service -Replicas 3 -PauseSeconds 60

# Create request logs in Identity and Workforce for the same dashboard.
.\scripts\live-log-demo.ps1 -Gateway https://localhost:8443 -Count 12
```

Open:

<http://localhost:3001/d/shift-happens-kubernetes-rollout>

The screenshot is [`docs/images/observability/kubernetes-rolling-update.png`](docs/images/observability/kubernetes-rolling-update.png).
The **Pods by version** panel shows **3 × v1.0.0**, then **3 × v1.0.0 + 1 × v1.0.1**, then **0 ×
v1.0.0 + 3 × v1.0.1**. The logs panel shows both services with the `log-demo-*` correlation ids. See
[`kubernetes/README.md`](kubernetes/README.md#rolling-update-demo-grafana) for the full explanation.

> The `5xx / min` is a stat panel on the normal Live System dashboard. It counts 5xx responses in
> the last minute (`increase(...[1m])`); it is the error-rate part of RED, not a separate dashboard.
> The `HighServerErrorRate` alert still owns the sustained error-rate check.

## 7. Stop or reset

```powershell
# stop, keep databases and volumes
docker compose -f docker-compose.microservices.yml --profile app down

# stop and wipe all local data
docker compose -f docker-compose.microservices.yml --profile app down -v

# start again and reseed
docker compose -f docker-compose.microservices.yml --profile app up -d
& 'C:\Program Files\Git\bin\bash.exe' scripts/seed-dev.sh
```

For Kubernetes, `kubectl delete namespace shift-happens` stops the app; hostPath PV data is retained.
See [`kubernetes/README.md`](kubernetes/README.md) for the teardown/reset details.

## 8. Common snags

| Symptom | Fix |
|---|---|
| Browser certificate warning | Accept Caddy’s local certificate once |
| `401` after restarting identity | Log in again; the signing key is regenerated in Compose |
| Empty demo data | Run `scripts/seed-dev.sh` and wait for the projections |
| Grafana has no Tempo datasource | Recreate Grafana after pulling: `docker compose -f docker-compose.microservices.yml up -d tempo grafana` |
| Autoscaling panel empty | It needs Kubernetes KEDA + kube-state-metrics; Compose alone is not enough |
| No e-mail | Check Mailpit; notification-service needs an employee replica with an address |
| Port already in use | Stop the other stack or change the published port |
| See what failed | `docker compose -f docker-compose.microservices.yml --profile app logs <service>` |

More detail: [`README.md`](README.md), [`docs/manual-testing.md`](docs/manual-testing.md),
[`kubernetes/README.md`](kubernetes/README.md), [`kubernetes/OBSERVABILITY.md`](kubernetes/OBSERVABILITY.md).

