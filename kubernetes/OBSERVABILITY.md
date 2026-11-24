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
port 9464, and the dashboard shows reporting services, JVM CPU and heap, HTTP rate and p95, and JDBC
pool usage.

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
