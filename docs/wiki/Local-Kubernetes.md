# Local Kubernetes

> **Note:** these steps were written from the manifests and `kubernetes/README.md` on `main` and have
> not been run end to end on either cluster type for this page. The Colima-specific commands in
> particular are untested. If a step is wrong, fix this page in a PR.

This runs the whole system from the images CI pushes to GHCR, using the plain manifests in
[`kubernetes/`](https://github.com/microservices-happens/shift-happens/tree/main/kubernetes). The
reference for every detail is
[`kubernetes/README.md`](https://github.com/microservices-happens/shift-happens/blob/main/kubernetes/README.md);
this page is the short path through it for both cluster types the team uses.

The older Kustomize scaffold in `k8s/` is not covered here; issue #64 decides which folder stays.

## What the manifests expect from the cluster

| Requirement | Where it comes from | What it means for you |
|---|---|---|
| One namespace for everything | No manifest sets `metadata.namespace` | Everything lands in the current context's namespace (normally `default`). Services find each other by short name (`identity-db`, `rabbitmq`, `JWT_JWKS_URL: http://identity-service:8080/...` in `kubernetes/shared/configmap.yaml`), so all of it must be in the same namespace |
| hostPath volumes | `kubernetes/<db>/pv.yaml`: `hostPath: /var/lib/shift-happens/<db>`, `type: DirectoryOrCreate`, `storageClassName: manual`, `persistentVolumeReclaimPolicy: Retain`, PVC bound by `volumeName` | Data lives on the node's file system (inside the Docker Desktop or Colima VM), survives `kubectl delete`, and does not depend on the cluster's default storage class. 10 PVs: `identity-db`, `workforce-db`, `scheduling-write-db`, `scheduling-read-db`, `leave-write-db`, `leave-read-db`, `notification-db`, `audit-db`, `ai-db`, `rabbitmq` |
| NodePorts | `api-gateway/service.yaml` 30443, `rabbitmq/service.yaml` (`rabbitmq-management`) 31672, `mailpit/service.yaml` (`mailpit-ui`) 30025 | The only ports opened to the host. Everything else is ClusterIP |
| Secrets created out of band | `kubernetes/secrets/create-secrets.sh` | No Secret is in git. Without them the pods stay in `CreateContainerConfigError` |
| Image pulls from GHCR and Docker Hub | `image: ghcr.io/microservices-happens/<service>:main`, `imagePullPolicy: Always` | Internet access, multi-arch images (amd64 + arm64). Your unmerged code is **not** in the cluster |

## 1. Pick the cluster

### Docker Desktop Kubernetes

The manifests were written for Docker Desktop's single-node **kubeadm** cluster
(Settings → Kubernetes → Enable Kubernetes).

```bash
kubectl config use-context docker-desktop
kubectl get nodes            # one node, Ready
```

### k3s via Colima

Colima runs k3s inside its VM when started with Kubernetes enabled. Give it enough memory for about
25 pods (every container has small requests, but the JVMs and Ollama add up).

```bash
colima start --kubernetes --cpu 4 --memory 8
kubectl config use-context colima
kubectl get nodes            # one node, Ready
```

The same manifests apply unchanged: the PVs are static hostPath volumes with `storageClassName: manual`,
so k3s's own `local-path` storage class is not used, and the hostPath directories are created inside the
Colima VM. If the NodePort URLs below do not answer from the Mac, use `kubectl port-forward` instead
(see [Reaching the services](#4-reach-the-services)).

## 2. Create the Secrets (once per cluster)

```bash
kubernetes/secrets/create-secrets.sh --generate
```

This writes two git-ignored files, `kubernetes/secrets/secrets.env` (from `secrets.env.example`, every
`CHANGE_ME` replaced by a random value) and `kubernetes/secrets/jwt-private-key.pem` (RSA 2048,
PKCS#8), unless they already exist. It then creates or updates one Secret per service in the current
context (`rabbitmq-secrets`, `identity-service-secrets`, `workforce-service-secrets`, ...; the full table is
in `kubernetes/README.md`).

- Check without touching the cluster: `kubernetes/secrets/create-secrets.sh --dry-run`.
- Another namespace: `NAMESPACE=<ns> kubernetes/secrets/create-secrets.sh`.
- After editing `secrets.env` (bootstrap admin, OIDC client secrets, SMTP), run the script again without
  `--generate` and restart what uses it, e.g. `kubectl rollout restart deployment/identity-service`.
- To log in at all you need an Administrator with a password: set `BOOTSTRAP_ADMIN_EMAIL` and
  `BOOTSTRAP_ADMIN_PASSWORD` in `secrets.env` (both or neither, password at least 8 characters).

**A cluster that already has data.** Postgres, Mongo and RabbitMQ read their password only when their
data directory is first initialised, and the hostPath PVs keep that directory. Either put the passwords
the data was created with (the old compose dev passwords such as `identity_dev`, `shift_dev`) into
`secrets.env` before running the script, or wipe the data (see [Teardown](#6-teardown)). A new
`PASSWORD_PEPPER` invalidates every stored password hash; reuse the old pepper to keep them. The old
shared Secret is no longer used: `kubectl delete secret shift-happens-secrets`.

## 3. Deploy

```bash
kubectl apply -R -f kubernetes/
kubectl get pods -w          # wait until everything is Running and 1/1 Ready
```

`-R` only reads `.yaml`/`.json`, so the script and env template in `kubernetes/secrets/` are skipped.
App pods may restart a few times until their database and RabbitMQ are up; that is expected.
`email-function` keeps cycling between `Completed` and `CrashLoopBackOff` by design (see
[Troubleshooting](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Troubleshooting.md#email-function-is-in-crashloopbackoff)).

Load the LLM once Ollama is up (the model lives as long as the pod; AI falls back to rule-based ranking
without it):

```bash
kubectl rollout status deployment/ollama
kubectl exec deployment/ollama -- ollama pull llama3.2:1b
```

### Using a namespace other than the current one

```bash
kubectl create namespace shift-happens
NAMESPACE=shift-happens kubernetes/secrets/create-secrets.sh --generate
kubectl apply -n shift-happens -R -f kubernetes/
```

PVs and NodePorts are cluster-wide, so only one copy of the stack can run per cluster, whatever the
namespace.

## 4. Reach the services

| What | NodePort URL | Fallback (any cluster) |
|---|---|---|
| Frontend + `/api/*` through the gateway | https://localhost:30443 (self-signed: `curl -k`, accept the browser warning) | `kubectl port-forward svc/api-gateway 8443:443` → https://localhost:8443 |
| RabbitMQ management | http://localhost:31672 (user `shift`, password `RABBITMQ_PASSWORD` from `kubernetes/secrets/secrets.env`) | `kubectl port-forward svc/rabbitmq 15672:15672` |
| Mailpit | http://localhost:30025 | `kubectl port-forward svc/mailpit 8025:8025` |
| Anything else | not exposed | `kubectl port-forward svc/<name> <local>:<port>` |

Google/GitHub login: the identity Deployment still has the compose callback URLs on port 8443
(`OIDC_REDIRECT_BASE_URL`, `FRONTEND_LOGIN_CALLBACK_URL`), so through NodePort 30443 the redirect goes
to the wrong port. Port-forwarding the gateway to 8443 (above) matches them, as long as the Compose
gateway is not also using 8443. Password login works either way.

Health check of one service from inside the cluster:

```bash
kubectl exec deployment/workforce-service -- curl -fsS http://localhost:8080/health
```

## 5. Status, logs, updates

```bash
kubectl get pods,svc
kubectl get pv,pvc
kubectl logs deployment/leave-command              # add -f to follow, --previous for the crashed container
kubectl describe pod -l app=leave-command          # events: pull errors, probe failures, OOM, missing Secret
```

New code reaches the cluster only through CI: merge to `main`, wait until the `ci` run on main has
pushed `ghcr.io/microservices-happens/<service>:main`, then restart the Deployment so
`imagePullPolicy: Always` pulls it:

```bash
kubectl rollout restart deployment/leave-command deployment/leave-query   # one image backs both roles
```

To try unmerged changes, use Docker Compose (it builds from local source) or run the service from the
IDE. To republish every image: Actions → `ci` → Run workflow on `main` with `services` = `all`.

Scheduling and leave each run as two Deployments from one image; `APP_ROLE` (`command` or `query`) in
the Deployment picks the role. See
[Troubleshooting](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Troubleshooting.md#a-cqrs-role-does-the-wrong-job).

## 6. Teardown

```bash
kubectl delete -R -f kubernetes/
```

The PVs use `Retain`, so the database data in `/var/lib/shift-happens/<db>` on the node survives. To
start with empty databases:

- **Docker Desktop:** Settings → Kubernetes → Reset Kubernetes Cluster.
- **Colima:** after the delete above, remove the directories inside the VM:
  `colima ssh -- sudo rm -rf /var/lib/shift-happens`.

The Secrets are not in the manifests, so `kubectl delete -R -f kubernetes/` leaves them; delete them
with `kubectl delete secret <name>` if you want to regenerate them.

## Differences from Docker Compose

From `kubernetes/README.md` ("Known issues"): the Caddy data is an `emptyDir` (new internal TLS
certificate on every gateway restart), the compose `internal: true` networks are not reproduced, Secrets
are plain base64 Kubernetes Secrets, there is no autoscaling yet (KEDA comes later), and email-function is
a Deployment instead of a KEDA ScaledJob.
