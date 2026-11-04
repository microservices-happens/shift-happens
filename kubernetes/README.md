# Shift Happens on local Kubernetes

Plain manifests for Docker Desktop Kubernetes (kubeadm), translated from
`docker-compose.microservices.yml` (the `app` profile, without `ollama`).
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

Our images are `luke3520/<service>:v1` (multi-platform: linux/amd64 + linux/arm64),
pulled with `imagePullPolicy: Always`.

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

## Rebuild, push and restart one service

Example: leave-service, which backs both `leave-command` and `leave-query`.

```bash
# once per machine: a builder that can do multi-platform builds
docker buildx create --name multi --driver docker-container --use

docker buildx build --platform linux/amd64,linux/arm64 \
  -t luke3520/leave-service:v1 --push ./services/leave-service

kubectl rollout restart deployment/leave-command deployment/leave-query
```

The frontend uses its own Dockerfile:

```bash
docker buildx build --platform linux/amd64,linux/arm64 \
  -t luke3520/frontend:v1 -f frontend/Dockerfile.microservices --push ./frontend
kubectl rollout restart deployment/frontend
```

## Where the images come from (read this before changing code)

- `kubectl apply` makes the cluster **pull every image from Docker Hub**. Our images are public,
  so no `docker login` is needed to pull, and Docker picks the right CPU architecture
  (amd64 on Windows, arm64 on Apple Silicon).
- `imagePullPolicy: Always` makes the cluster check Docker Hub for the newest `:v1` on every pod start.
- **Docker Compose does not use these images.** `docker compose -f docker-compose.microservices.yml
  --profile app up --build` still builds from your local source.
- **The images are frozen at the code they were built from** (main, 2026-11-04). If you change a service,
  `kubectl apply` alone still runs the old image: rebuild + push + restart (section above).
- **Only the `luke3520` account can push** to `luke3520/*`. Teammates can pull, not push.
  See "Next step: images from CI" below.
- Docker Hub limits anonymous pulls to about 100 per 6 hours per IP. One full start pulls about 20 images.
  If several people on the same network hit the limit, `docker login` raises it.

### Next step: images from CI

`.github/workflows/ci.yml` already builds every changed service and, on a merge to `main`, pushes
`ghcr.io/microservices-happens/<service>:<commit-sha>` to GitHub Container Registry. To use those
images here instead of `luke3520/*`:

1. Build multi-platform in CI: `platforms: linux/amd64,linux/arm64` with `push: true`
   (`load: true` only supports a single platform).
2. Also push a moving tag such as `:main`, and add the frontend to the image matrix.
3. Make the GHCR packages public (org → Packages → package settings), or add an `imagePullSecret`.
4. Change the `image:` lines in `kubernetes/*/deployment.yaml` to `ghcr.io/microservices-happens/<service>:main`.

Then nobody pushes images by hand: merge to main → CI pushes → `kubectl rollout restart deployment/<name>`.

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
- **ollama** (optional `ai` profile) is not deployed. ai-service falls back to rule-based ranking.
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
