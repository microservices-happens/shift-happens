# 0005. Plain manifests in `kubernetes/` instead of Kustomize

- **Status:** Accepted (2026-11-22)
- **Date:** 2026-11-04

## Context
The course requires the system to run on Kubernetes. The first plan was a kind or minikube cluster with a Kustomize base and a local overlay in `k8s/`, scaffolded on 2026-09-23 and not used. PR #28 delivered the first working setup a different way: plain manifests based on the teacher's demo, translated from `docker-compose.microservices.yml` and verified on Docker Desktop Kubernetes.

Both folders existed for a while. The useful unique parts of `k8s/` (namespace and Ingress) were ported, the KEDA/HPA manifests in `kubernetes/autoscaling/` are the maintained ones, and `k8s/` was removed on 2026-11-22.

## Decision
- The manifests in `kubernetes/` are the ones we deploy: one folder per Compose service, applied with `kubectl apply -n shift-happens -R -f kubernetes/` after the namespace exists.
- `kubernetes/ingress.yaml` provides the ingress-nginx rules; `kubernetes/namespace.yaml` defines the namespace.
- Autoscaling lives in `kubernetes/autoscaling/`: CPU HPAs for the query roles and frontend, KEDA for queue consumers. Metrics-server is documented in `kubernetes/metrics-server/README.md`.
- Every Kubernetes Service has the name of its Compose service, so environment variables (`DB_HOST=identity-db`, `rabbitmq`, ...) are the same in both.
- The target is Docker Desktop Kubernetes (kubeadm); Colima/k3s is documented in the wiki.
- Images are pulled from GHCR as `ghcr.io/microservices-happens/<service>:main`. CI builds and pushes them for amd64 and arm64 on every merge to `main`.

## Consequences
- **Easier:** one deployment tree, one command, no Kustomize knowledge; manifests can be read next to the Compose file.
- **Harder:** there is one environment only, and anything shared between manifests is repeated. Local code is not in the cluster until CI has pushed an image. `:main` is a moving tag; use a digest for immutable production deployments.
- **Autoscaling is now demonstrable:** metrics-server is installed, the frontend HPA scaled 2 → 5 under load, and the email KEDA ScaledJob starts/completes Jobs per queue burst.
- Open items: Kubernetes observability stack (Prometheus/Loki/Grafana/Tempo), ingress-nginx installation, network policies and a managed secret store.

## Alternatives considered
- Kustomize base and overlay: the original plan; removed because the plain manifests were already working and maintained.
- Images pushed by hand to a personal Docker Hub account: used to verify PR #28, then replaced by CI-built GHCR images.
