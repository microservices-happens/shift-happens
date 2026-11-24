# Autoscaling

```mermaid
flowchart LR
    P[producer / notification-service] -->|notification.email.requested.v1| X((shift-happens.events))
    X -->|binding| Q[email.send queue]
    Q -->|queue length| K[KEDA RabbitMQ scaler]
    K -->|creates up to 5| J[email-function Jobs]
    J -->|consume + SMTP| M[Mailpit / provider]
    Q -->|drained| K
    J -->|Complete| K
    K -->|metrics| PR[Prometheus]
    PR --> G[Grafana KEDA panels]
```

Two mechanisms, both declared here:

| Manifest | Mechanism | Target |
|---|---|---|
| `hpa.yaml` | CPU-based HorizontalPodAutoscaler (needs metrics-server) | query roles and frontend |
| `keda.yaml` | KEDA `ScaledObject` on queue length | `notification-service` |
| `keda.yaml` | KEDA `ScaledJob` on queue length | `email-function` (one Job per burst, exits when idle) |

## Install KEDA in Docker Desktop Kubernetes

The CRDs are too large for client-side apply in this cluster; use server-side apply:

```bash
kubectl apply --server-side --force-conflicts -f https://github.com/kedacore/keda/releases/download/v2.17.0/keda-2.17.0.yaml
kubectl apply -n keda -f kubernetes/autoscaling/keda-metrics-nodeport.yaml.example
kubectl -n keda get pods
```

`keda-metrics-nodeport.yaml.example` exposes the operator metrics on NodePort **32073** so the Compose
Prometheus can scrape KEDA from outside the cluster.

## CPU HPA and metrics-server

The HPAs need the metrics API. Install metrics-server once (local Docker Desktop needs the
insecure-kubelet flag because its kubelet certificate is self-signed):

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.1/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json --patch '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
kubectl -n kube-system rollout status deployment/metrics-server
kubectl top nodes
```

Apply `hpa.yaml` only after the target Deployments exist. The frontend HPA was verified with a
`hey` load generator: CPU passed the 60% target, replicas went **2 → 5**, and after the load stopped
the HPA returned to 2. Details and the production caveats are in `../metrics-server/README.md`.

The production manifest also contains a `notification-service` ScaledObject. It assumes the full
`kubernetes/` stack has been applied; the email-only local demo deliberately deploys just RabbitMQ,
Mailpit and the ScaledJob.

## Local demo: email ScaledJob

The production manifest points at the GHCR image. For a local image, tag it and patch the ScaledJob:

```bash
docker tag shift-happens/email-function:dev shift-happens/email-function:keda
kubectl -n shift-happens patch scaledjob email-function --type=json --patch '[{"op":"replace","path":"/spec/jobTargetRef/template/spec/containers/0/image","value":"shift-happens/email-function:keda"}]'
```

Prerequisites: `rabbitmq` and `mailpit` Deployments/Services, the `rabbitmq-secrets`,
`email-function-secrets` and `rabbitmq-keda` Secrets, and the TriggerAuthentication in `keda.yaml`.
The `email.send` queue can be created by the first Job or through the RabbitMQ management API; it must
have `x-dead-letter-exchange=shift-happens.dlx` to match the function's declaration.

```bash
kubectl create namespace shift-happens
kubectl -n shift-happens create secret generic rabbitmq-secrets --from-literal=RABBITMQ_DEFAULT_PASS=shift_dev
kubectl -n shift-happens create secret generic email-function-secrets --from-literal=RABBITMQ_PASSWORD=shift_dev --from-literal=SMTP_USERNAME='' --from-literal=SMTP_PASSWORD=''
kubectl -n shift-happens create secret generic rabbitmq-keda --from-literal=host='amqp://shift:shift_dev@rabbitmq.shift-happens.svc.cluster.local:5672/shift-happens'
```

Port-forward the management API and generate a burst:

```bash
kubectl -n shift-happens port-forward svc/rabbitmq 15672:15672
node scripts/keda-email-burst.mjs 500
kubectl -n shift-happens get jobs
kubectl -n shift-happens get jobs -w
```

Observed in this environment: queue demand `500`, KEDA started **5** Jobs (the configured
`maxReplicaCount`), Mailpit received **500** messages, each Job completed in about 12 s, and the queue
returned to 0. The KEDA operator metrics are exposed locally by `keda-metrics-nodeport.yaml.example` on
NodePort 32073 and scraped by Compose Prometheus (job `keda`).

## Production notes

- Keep the GHCR image immutable by tag/digest; `imagePullPolicy: Always` is acceptable for `:main`,
  but a digest is safer.
- Set resource requests/limits and a PodDisruptionBudget for the consumers; the ScaledJob already has
  history limits and a 5-replica cap.
- Alert on `keda_scaled_job_errors_total`, `rabbitmq_queue_messages_ready` and DLQ depth.
- The ScaledJob sets `EMAIL_FUNCTION_EXIT_WHEN_IDLE=true`; the function uses `halt(0)` after a drained
  queue so the Job really completes. A graceful context close can block on listener shutdown.
