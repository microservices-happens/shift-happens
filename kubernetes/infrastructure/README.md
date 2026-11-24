# Cluster infrastructure

Installed with Helm or the vendor manifests, not hand-written here. Pin chart versions when this is automated.

| Component | Chart / manifest | Notes |
|---|---|---|
| Ingress controller | `ingress-nginx/ingress-nginx` | Replaces Caddy; applies `../ingress.yaml` |
| Metrics server | `kubernetes-sigs/metrics-server` | Required by the HPAs; see `../metrics-server/README.md` |
| KEDA | `kedacore/keda` | Email ScaledJob and queue-based notification scaling |
| RabbitMQ | `bitnami/rabbitmq` or RabbitMQ Cluster Operator | Quorum queues for HA |
| PostgreSQL (one per service) | `bitnami/postgresql` | Or CloudNativePG operator |
| MongoDB (scheduling-read, leave-read) | `bitnami/mongodb` | |
| Observability | `grafana/k8s-monitoring` or kube-prometheus-stack + Loki + Tempo | OTel Collector → Prometheus/Loki/Tempo → Grafana |
| Ollama | `otwld/ollama` | CPU is enough for `llama3.2:1b` |
