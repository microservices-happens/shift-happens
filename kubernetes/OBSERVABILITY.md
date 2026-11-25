# Observability and smoke checks

Use Docker Compose to try the Java agent and Tempo before service images are published. Use
Kubernetes to see telemetry from the deployed pods. Each environment has its own Grafana instance.

## Docker Compose

From the repository root, start Identity, the Collector and Grafana. Compose starts their dependencies.

```powershell
docker compose -f docker-compose.microservices.yml --profile app up -d --build identity-service otel-collector grafana
docker compose -f docker-compose.microservices.yml --profile app exec identity-service curl -fsS http://localhost:8080/health
```

Open Grafana at [http://localhost:3001](http://localhost:3001). The Compose login uses `GRAFANA_USER`
and `GRAFANA_PASSWORD` (development defaults are `admin` / `admin`). In **Explore**, select **Tempo**
and run this TraceQL query after the health request returns `UP`.

```text
{ resource.service.name = "identity-service" }
```

The result should include a recent `GET /health` trace. The existing **Shift Happens — Live System**
dashboard still uses Prometheus for metrics and Loki/Promtail for container logs. To generate an
asynchronous business event, bring up `ai-service` and run `.\scripts\ai-demo.ps1`. Look for AI service
activity and traces. The event goes through RabbitMQ and may leave the HTTP request rate unchanged.

## Follow a REST request through RabbitMQ

Use a seeded local Compose stack, including `scheduling-command` and `ai-service`. Build the updated
images and follow the seed instructions in the [root README](../README.md). Run the demo with Node.js
from PowerShell. Use your seeded Manager password.

```powershell
$env:TRACE_DEMO_PASSWORD = Read-Host 'Seeded Manager password' -MaskInput
node scripts/observability-demo.mjs
Remove-Item Env:TRACE_DEMO_PASSWORD
```

The script creates its own shift and assignment and cancels that assignment through the gateway.
It prints a trace ID and correlation ID, then checks that Tempo finds both Scheduling and AI spans
in the same trace. In Grafana **Explore → Tempo**, select **Trace ID**, paste the ID and run the query.
Find the REST request, RabbitMQ publish and AI consumer spans. Open the REST and consumer spans
to see `app.correlation_id`. The script keeps the demo shift for inspection.

The dashboards show timeout counts and the percentage of saved AI suggestions produced by the rule
fallback over the last 15 minutes. Wait for Prometheus to scrape both counters before generating
events so that Grafana has a baseline. To exercise fallback in Compose, leave Ollama stopped and
run the trace demo. To generate a saga timeout, see the [timeout and compensation walkthrough](../docs/diagrams/observability-dlq-recovery.md).

## Kubernetes

Use the `shift-happens` namespace. From the repository root in Git Bash, create the namespace and
run the existing secrets script. `--generate` adds a Grafana password to an existing `secrets.env`
when needed and keeps existing database credentials.

```bash
kubectl config use-context docker-desktop
kubectl apply -f kubernetes/namespace.yaml
NAMESPACE=shift-happens bash kubernetes/secrets/create-secrets.sh --generate
kubectl -n shift-happens apply -R -f kubernetes/shared -f kubernetes/tempo -f kubernetes/loki -f kubernetes/prometheus -f kubernetes/grafana -f kubernetes/otel-collector
kubectl -n shift-happens rollout restart deployment/otel-collector
kubectl -n shift-happens rollout status deployment/grafana
kubectl -n shift-happens get pods,svc,pvc
```

Open Grafana at [http://localhost:30300](http://localhost:30300). For a fresh Grafana volume, log in
as `admin` with `GRAFANA_ADMIN_PASSWORD` from `kubernetes/secrets/secrets.env`. An existing Grafana
volume retains its current login. Go to **Dashboards → Shift Happens → Shift Happens - Observability**.
The Collector sends traces to Tempo and logs to Loki. Prometheus scrapes its workload metrics on
port 9464, and the dashboard shows reporting services, JVM CPU and heap, HTTP rate and p95, JDBC pool usage, saga timeouts and AI fallback rate.

Once CI publishes the updated service images, apply their manifests as described in [README.md](README.md)
and restart any application deployments still running older images. With Identity ready, generate
a fresh trace inside the cluster and run the same Tempo query in Kubernetes Grafana.

```powershell
kubectl -n shift-happens exec deployment/identity-service -- curl -fsS http://localhost:8080/health
kubectl -n shift-happens logs deployment/otel-collector --tail=100
```

The Kubernetes dashboard shows application metrics. Kubernetes pod resource usage and KEDA queue
scaling have their own checks in [autoscaling/README.md](autoscaling/README.md). Email telemetry from
the KEDA ScaledJob still needs a smoke check.

For the **rolling update and live log** demo, use the Compose Grafana at
<http://localhost:3001/d/shift-happens-kubernetes-rollout>. It reads the Kubernetes
kube-state-metrics NodePort and the Compose Loki/Promtail streams. The commands and captured
screenshot are in [README.md](README.md#rolling-update-demo-grafana).

To run the same demo against a seeded local Kubernetes deployment, expose Tempo in another terminal
with `kubectl -n shift-happens port-forward svc/tempo 3200:3200` and set
`$env:TRACE_DEMO_GATEWAY = 'https://localhost:30443'` before running the script.
The Compose and Kubernetes Tempo endpoints use the same local port. Run one of them at a time.
