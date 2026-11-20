# 0005. Plain manifests in `kubernetes/` instead of Kustomize

- **Status:** Proposed
- **Date:** 2026-11-04

## Context
The course requires the system to run on Kubernetes. The plan (P7.1, P7.2) was a kind or minikube cluster with a Kustomize base and a local overlay in `k8s/`, scaffolded on 2026-09-23 and not used since (issue #64). PR #28 delivered the first working setup a different way: plain manifests based on the teacher's demo, translated from `docker-compose.microservices.yml` and verified on Docker Desktop Kubernetes.

PR #28 left both folders in place and asked the team to pick one. That choice is still open (issue #64), so this ADR stays Proposed until it is closed. Why plain manifests were preferred over finishing the Kustomize scaffold is not written down beyond "based on the teacher's demo": **rationale not recorded**.

## Decision
- The manifests in `kubernetes/` are the ones we deploy: one folder per Compose service, applied with `kubectl apply -R -f kubernetes/`. No Kustomize, no overlays.
- Every Kubernetes Service has the name of its Compose service, so the environment variables (`DB_HOST=identity-db`, `rabbitmq`, ...) are the same in both.
- The target is Docker Desktop Kubernetes (kubeadm).
- Our images are pulled from GHCR as `ghcr.io/microservices-happens/<service>:main`. CI builds and pushes them for amd64 and arm64 on every merge to `main`. Nobody pushes images by hand.

## Consequences
- **Easier:** the manifests can be read next to the Compose file, and a teammate on Windows or Apple Silicon starts the whole system with one command and no local image build.
- **Harder:** there is one environment only, and anything shared between manifests is repeated. Local code is not in the cluster until it is merged and CI has pushed the image. `:main` is a moving tag.
- The HPA and the KEDA ScaledJob for email-function exist only in `k8s/` and must be ported before that folder is removed (issue #64). Until then email-function runs as a Deployment that restarts when its queue is empty.
- `README.md`, `docs/implementation-plan.md` and `docs/project-plan.md` (P7.1, P7.2) still describe the Kustomize setup and must be corrected.
- Open items listed in `kubernetes/README.md`: one shared Secret with development defaults (issue #66), no Ingress (issue #65), no network isolation.

## Alternatives considered
- Kustomize base and overlay in `k8s/` (the original plan): why not is not recorded.
- Images pushed by hand to a personal Docker Hub account: used to verify PR #28, then replaced by CI-built GHCR images before the merge so that nobody builds or pushes by hand.
