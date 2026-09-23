# Cluster infrastructure

Installed with Helm, not hand-written manifests. Pin chart versions in `helmfile.yaml` when this is set up.

| Component | Chart (suggestion) | Notes |
|---|---|---|
| Ingress controller | `ingress-nginx/ingress-nginx` | Replaces Caddy |
| KEDA | `kedacore/keda` | For `email-function` ScaledJob and optional queue-based scaling |
| RabbitMQ | `bitnami/rabbitmq` or RabbitMQ Cluster Operator | Quorum queues for HA |
| PostgreSQL (one per service) | `bitnami/postgresql` | Or CloudNativePG operator |
| MongoDB (scheduling-read, leave-read) | `bitnami/mongodb` | |
| Observability | `grafana/k8s-monitoring` or kube-prometheus-stack + loki + tempo | OTel Collector → Prometheus/Loki/Tempo → Grafana |
| Ollama | `otwld/ollama` | CPU is enough for `llama3.2:1b` |
