# Shift Happens on local Kubernetes

Plain manifests for Docker Desktop Kubernetes (kubeadm), translated from
`docker-compose.microservices.yml` (the `app` profile, with Ollama deployed alongside the AI service).
One folder per compose service; each Kubernetes Service has the same name as
its compose service, so hostnames like `DB_HOST=identity-db` work unchanged.

```
kubernetes/
  shared/            ConfigMap service-env (x-service-env) + Secret shift-happens-secrets
  <service>/         deployment.yaml, service.yaml
  <database>/        + pv.yaml (hostPath PV, storageClassName manual, Retain + PVC)
  api-gateway/       + configmap.yaml (Caddyfile copied from compose)
  otel-collector/    + configmap.yaml (copy of docker/microservices/otel-collector.yml)
```

Our images are `ghcr.io/microservices-happens/<service>:main`, built and pushed by CI on every
merge to `main` (multi-platform: linux/amd64 + linux/arm64), pulled with `imagePullPolicy: Always`.

## Prerequisites

```bash
kubectl config use-context docker-desktop
kubectl get nodes            # one node, Ready
```

## Deploy

```bash
kubectl apply -R -f kubernetes/
kubectl get pods -w          # wait until everything is Running and 1/1 Ready
```

App pods may restart a few times until their database and RabbitMQ are up. That is expected.

## Status

```bash
kubectl get pods,svc
kubectl get pv,pvc
```

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

The PVs use `Retain` and hostPath `/var/lib/shift-happens/<db>` on the Docker Desktop VM,
so database data survives a teardown. To start with empty databases, reset the Kubernetes
cluster in Docker Desktop (Settings → Kubernetes → Reset Kubernetes Cluster).

## Localhost URLs

| Service | Kubernetes type | URL |
|---|---|---|
| api-gateway (frontend + `/api/*`) | NodePort 30443 | https://localhost:30443 (self-signed: `curl -k` / accept the browser warning) |
| rabbitmq management UI | NodePort 31672 (`rabbitmq-management`) | http://localhost:31672 (user `shift`, password in `shared/secret.yaml`) |
| mailpit UI | NodePort 30025 (`mailpit-ui`) | http://localhost:30025 |
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
- **email-function** has no Service and no probe: it has no ports and no healthcheck in compose.
  It exits when `email.send` is empty, so as a Deployment it keeps restarting (`Completed` →
  `CrashLoopBackOff` with growing back-off). Compose does the same with `restart: unless-stopped`.
  The intended fix is a KEDA ScaledJob (0 pods when the queue is empty).
- **GraphQL** (`/api/graphql`) returns 404: the query role does not implement it yet
  (see `services/scheduling-service/src/main/resources/graphql/README.md`). Routing through the gateway works.
- **Caddy data** (`/data`, `/config`) is an `emptyDir`, so the internal TLS certificate is regenerated
  when the gateway pod restarts.
- Compose network isolation (`internal: true` networks) is not reproduced; all pods can reach each other.
- Secrets hold the compose dev defaults in plain `stringData`. Fine for a local demo, not for a real cluster.
- No autoscaling yet (KEDA comes later). Every container has small requests/limits.
- The older Kustomize scaffold in `/k8s` is separate from this folder and is not used here.
