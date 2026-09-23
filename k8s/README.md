# Kubernetes (production-like, local)

```
k8s/
├── base/                 # Kustomize base: one Deployment + Service per service role, Ingress, HPA, KEDA
│   ├── services/         # generated from the same list as docker-compose.microservices.yml
│   └── keda/             # serverless email-function (ScaledJob)
├── overlays/local/       # kind/minikube: local image tags
└── infrastructure/       # Helm-installed dependencies (ingress, KEDA, DBs, RabbitMQ, observability)
```

Quick start (once images exist):

```bash
kind create cluster --name shift-happens
# install infrastructure (see infrastructure/README.md)
kubectl apply -k k8s/overlays/local
```

Every Deployment uses `/health/readiness` and `/health/liveness` (Spring Boot probe groups) and reads shared
settings from the `shift-happens-config` ConfigMap. Secrets (DB passwords, OIDC client secrets) come from
`<service>-secrets` and are never committed.
