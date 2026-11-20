# Troubleshooting

Only problems that the code, the READMEs, the CI comments or the commit history show have happened
or can happen in this project. Each entry: symptom, cause, fix. CI-only problems (Maven Central 403,
Trivy, a `ci` check stuck on "Expected", a missing image in `system-tests`) are in the table in
[`docs/ci-demo.md`](https://github.com/microservices-happens/shift-happens/blob/main/docs/ci-demo.md#troubleshooting).

**Contents**

- Kubernetes: [pod stuck in `CreateContainerConfigError`](#pod-stuck-in-createcontainerconfigerror) ·
  [database or RabbitMQ rejects the password](#a-database-or-rabbitmq-rejects-the-password-after-creating-the-secrets) ·
  [`ImagePullBackOff` or old code in the cluster](#imagepullbackoff-or-the-cluster-runs-old-code) ·
  [email-function in `CrashLoopBackOff`](#email-function-is-in-crashloopbackoff) ·
  [Google/GitHub login lands on the wrong port](#googlegithub-login-redirects-to-the-wrong-port)
- Identity and login: [identity-service does not start](#identity-service-does-not-start) ·
  [every request is 401 after identity restarts](#every-request-is-401-after-identity-service-restarts) ·
  [no login works after new Secrets](#no-password-login-works-after-new-secrets)
- Messaging: [employees missing in identity, scheduling or leave](#employees-exist-in-workforce-but-not-in-identity-scheduling-or-leave) ·
  [messages in a `.dlq`](#messages-pile-up-in-a-dlq) ·
  [a CQRS role does the wrong job](#a-cqrs-role-does-the-wrong-job) ·
  [AI suggestions all say `source: rule`](#ai-suggestions-all-have-source-rule)
- Build and database: [Flyway fails at startup](#flyway-fails-at-startup) ·
  [`docker build` fails on `COPY --from=contracts`](#docker-build-fails-on-copy---fromcontracts) ·
  [`*IT` tests cannot find Docker under Colima](#it-tests-cannot-find-docker-under-colima) ·
  [port already in use](#port-already-in-use)

---

## Kubernetes

### Pod stuck in `CreateContainerConfigError`

**Symptom.** `kubectl get pods` shows `CreateContainerConfigError` for a service and its database;
`kubectl describe pod -l app=<name>` reports a missing secret, e.g. `secret "identity-service-secrets" not found`.

**Cause.** No Secret is committed. Every Deployment reads its passwords (and identity its JWT key) from a
per-service Secret that you create from local files (`kubernetes/README.md`, "Secrets").

**Fix.**

```bash
kubernetes/secrets/create-secrets.sh --generate      # first time; later runs without --generate
kubectl get pods -w
```

If you deployed into another namespace, pass it: `NAMESPACE=<ns> kubernetes/secrets/create-secrets.sh`.

### A database or RabbitMQ rejects the password after creating the Secrets

**Symptom.** App pods restart forever; `kubectl logs deployment/<service> --previous` shows the database or
the broker refusing the login. Typical after moving from the old shared Secret to the per-service ones.

**Cause.** Postgres, Mongo and RabbitMQ only read their password when the data directory is first
initialised, and the hostPath PVs (`/var/lib/shift-happens/<db>`, `Retain`) keep that directory. The new
random passwords from `--generate` do not match the ones the data was created with.

**Fix.** Either put the original passwords (the old compose dev ones, `identity_dev`, `shift_dev`, ...)
into `kubernetes/secrets/secrets.env` and re-run `kubernetes/secrets/create-secrets.sh`, then
`kubectl rollout restart deployment/<name>`; or wipe the data
([Local Kubernetes → Teardown](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Local-Kubernetes.md#6-teardown))
so the new passwords are used. Remove the unused old Secret: `kubectl delete secret shift-happens-secrets`.

### `ImagePullBackOff`, or the cluster runs old code

**Symptom.** A pod is in `ImagePullBackOff`, or your change is merged but the cluster still behaves the old way.

**Cause.** The cluster pulls `ghcr.io/microservices-happens/<service>:main` (built only by CI on `main`)
and the public images from Docker Hub. Known reasons: the GHCR package is not public yet (org admin,
one-time setup in `kubernetes/README.md`), the `ci` run on main has not pushed yet, the pod was not
restarted, or the Docker Hub anonymous limit (about 100 pulls per 6 hours per IP; one full start pulls
about 11 public images) was hit by several people on the same network.

**Fix.**

```bash
kubectl describe pod -l app=<name>                      # the pull error is in Events
kubectl rollout restart deployment/<name>               # pull the newest :main
docker login                                            # raises the Docker Hub limit
```

Unmerged code is never in the cluster. Use Compose (`docker compose -f docker-compose.microservices.yml --profile app up --build`) to try it.

### email-function is in `CrashLoopBackOff`

**Symptom.** `email-function` alternates between `Completed` and `CrashLoopBackOff` with a growing back-off.

**Cause.** Expected. It exits when the `email.send` queue is empty and, as a plain Deployment, is restarted.
The intended fix is a KEDA ScaledJob (`kubernetes/README.md`, "Known issues").

**Fix.** None needed.

### Google/GitHub login redirects to the wrong port

**Symptom.** Third-party login in Kubernetes ends on `https://localhost:8443/...` while the gateway is on `:30443`.

**Cause.** `OIDC_REDIRECT_BASE_URL` and `FRONTEND_LOGIN_CALLBACK_URL` in
`kubernetes/identity-service/deployment.yaml` are copied from Compose.

**Fix.** Use `kubectl port-forward svc/api-gateway 8443:443` and open https://localhost:8443 (stop the
Compose gateway first, it uses the same port). Password login is not affected.

---

## Identity and login

### identity-service does not start

**Symptom.** identity-service exits at startup (Compose or Kubernetes) complaining about the pepper.

**Cause.** It refuses to start without `PASSWORD_PEPPER` (`services/identity-service/README.md`; this is
what commit `30aea3b` fixed in the Kubernetes manifests).

**Fix.** Compose falls back to a dev default when `.env` does not set it. In Kubernetes the
value comes from `identity-service-secrets`: re-run `kubernetes/secrets/create-secrets.sh` and check
`kubernetes/secrets/create-secrets.sh --dry-run` lists `PASSWORD_PEPPER`.

### Every request is 401 after identity-service restarts

**Symptom.** After `docker compose -f docker-compose.microservices.yml restart identity-service` (or a rebuild), the frontend gets `401` from every service
until you log in again.

**Cause.** Without `JWT_PRIVATE_KEY_PATH` identity generates a new RS256 key at startup, so tokens signed
with the old key no longer validate against the JWKS (`docker-compose.microservices.yml`: "Unset = a
generated key, so tokens are invalid after a restart"). Workforce, scheduling and ai validate the JWT
themselves against identity's JWKS.

**Fix.** Log in again. In Kubernetes this does not happen: the key is mounted from
`identity-service-secrets` at `/etc/identity-service/jwt/private-key.pem`. If it does, check the Secret has
the `jwt-private-key.pem` key (`kubernetes/secrets/create-secrets.sh --dry-run`).

### No password login works after new Secrets

**Symptom.** Accounts exist but every password is wrong.

**Cause.** Passwords are `bcrypt(PASSWORD_PEPPER + password)`. A new pepper (e.g. from `--generate` on a
cluster with existing identity data) invalidates every stored hash.

**Fix.** Put the old pepper back into `kubernetes/secrets/secrets.env`, re-run the script and
`kubectl rollout restart deployment/identity-service`; or wipe the identity data and start over.

---

## Messaging

### Employees exist in workforce but not in identity, scheduling or leave

**Symptom.** After loading demo data or creating employees, nobody can log in, or scheduling/leave reject the
employee as unknown.

**Cause.** The exchange `shift-happens.events` is a topic exchange: a message whose routing key matches no
bound queue is dropped. Each consumer declares its own queue when it starts, so events published before the
consumer has ever started are lost for it (`scripts/seed-dev.sh` header). Workforce's outbox does its job:
the row is marked published.

**Fix.** Start the consumers before you publish. `scripts/seed-dev.sh` checks this for you: it fails if
`identity.accounts` is missing and warns for `scheduling.command`, `scheduling.projection`,
`leave.command` and `leave.projection`. Check by hand:

```bash
docker compose -f docker-compose.microservices.yml exec -T rabbitmq rabbitmqctl -q list_queues -p shift-happens name messages consumers
kubectl exec deployment/rabbitmq -- rabbitmqctl -q list_queues -p shift-happens name messages consumers
```

To get the lost events through, start from empty workforce data and seed again once the queues exist (the
seed only runs on an empty database; reset with `docker compose -f docker-compose.microservices.yml down -v`, which wipes every Compose volume).

### Messages pile up in a `.dlq`

**Symptom.** `<queue>.dlq` (e.g. `identity.accounts.dlq`, `leave.command.dlq`) has messages.

**Cause.** A consumer failed on a message. identity, scheduling (both roles) and leave retry three times
in-process and then republish it to `shift-happens.dlx`; ai-service has no retry advice, so it dead-letters
on the first failure. Typical causes in the code: a payload that cannot be applied (e.g. a snapshot without
`employeeId` throws), or a bug in the handler.

Be careful reading the DLQs, the services route dead letters differently:

| Consumer | Dead-letter routing key | DLQ binding on `shift-happens.dlx` | Result |
|---|---|---|---|
| leave-command, leave-query | the queue name (`leave.command`, ...) | exactly the queue name | the DLQ only holds that queue's failures |
| identity, scheduling-command, scheduling-query, ai | the original `eventType` | `#` | **every** one of these DLQs receives a copy of every dead letter from any service, so a message in `identity.accounts.dlq` is not necessarily identity's failure |

**Fix.**

1. Find which consumer failed: open the RabbitMQ management UI (Compose http://localhost:15672,
   Kubernetes http://localhost:31672) → Queues → `<queue>.dlq` → Get messages. Messages republished by the
   retry recoverer (identity, scheduling, leave) carry `x-exception-message`, `x-original-exchange` and
   `x-original-routingKey` headers; ai-service's are dead-lettered by the broker and carry `x-death` instead.
2. Read the consumer's log around that time: `docker compose -f docker-compose.microservices.yml logs <service>` or `kubectl logs deployment/<service>`.
   The identity and scheduling-command listeners log `Received <eventType> for ... (v<version>), correlation <id>`
   for every message.
3. Fix the cause and deploy. Dead letters are never replayed automatically. To replay one, publish the
   envelope again from the management UI to exchange `shift-happens.events` with routing key = its
   `eventType`. That reaches every bound queue, which is safe: consumers skip an `eventId` they have
   already processed and ignore older versions.
4. Empty a DLQ when you are done: `rabbitmqctl purge_queue -p shift-happens <queue>.dlq` (through
   `docker compose -f docker-compose.microservices.yml exec -T rabbitmq` or `kubectl exec deployment/rabbitmq --`).

### A CQRS role does the wrong job

**Symptom.** A `scheduling-query` or `leave-query` instance tries to reach Postgres on `localhost:5432` and
crashes, or GraphQL is missing, or the wrong queue fills up.

**Cause.** scheduling-service and leave-service are one image with two roles. `spring.profiles.active` is
`${APP_ROLE:command}`, so a deployment without `APP_ROLE` silently starts as the **command** role (Postgres,
outbox, `scheduling.command` / `leave.command` consumers).

**Fix.** Set `APP_ROLE=query` on the query deployment, as `docker-compose.microservices.yml` and
`kubernetes/{scheduling,leave}-query/deployment.yaml` do, along with the role's `RABBITMQ_QUEUE`
(`scheduling.projection`, `leave.projection`). For GraphiQL locally, add the `dev` profile:
`APP_ROLE=query,dev`.

### AI suggestions all have `source: rule`

**Symptom.** Replacement suggestions arrive, but always ranked by the rule-based fallback.

**Cause.** ai-service falls back when the LLM is slow or down. In Kubernetes the Ollama pod starts without a
model, and the model is gone again after the pod is replaced.

**Fix.**

```bash
kubectl rollout status deployment/ollama
kubectl exec deployment/ollama -- ollama pull llama3.2:1b
```

---

## Build and database

### Flyway fails at startup

**Symptom.** A service (or its `*IT`) fails at startup with a Flyway error: two migrations with the same
version, or a validation/checksum failure. Hibernate runs with `ddl-auto: validate`, so a schema that
does not match the entities also stops startup.

**Cause.** Migrations are numbered per service in `services/<svc>/src/main/resources/db/migration`
(`V1__init.sql`, `V2__...`). Two branches that both add the next number clash after merge; and a migration
that was renamed or edited after it ran on your local database no longer matches its recorded checksum.
This has happened: commit `30aea3b` renumbered identity's `V4__oidc.sql` to `V3__oidc.sql`.

**Fix.**

```bash
ls services/<svc>/src/main/resources/db/migration       # take the next free number after rebasing on main
```

Never edit a migration that is on `main`; add a new one. If your local database ran a migration that has
since been renamed or changed, wipe that service's data (Compose: `docker compose -f docker-compose.microservices.yml down -v` wipes all volumes;
Kubernetes: see [Teardown](https://github.com/microservices-happens/shift-happens/blob/main/docs/wiki/Local-Kubernetes.md#6-teardown)).
`*IT` tests start a fresh Testcontainers database, so they show the clash, not the checksum problem.

### `docker build` fails on `COPY --from=contracts`

**Symptom.** Building a service image by hand stops at `COPY --from=contracts openapi/<service>.yaml ...`.

**Cause.** The Dockerfiles copy the OpenAPI contract for Swagger UI from a named build context that lies
outside the service folder. Compose passes it (`additional_contexts: contracts: ./contracts`), a plain
`docker build` does not.

**Fix.** From the repo root:

```bash
docker build --build-context contracts=contracts -t shift-happens/workforce-service:ci services/workforce-service
```

### `*IT` tests cannot find Docker under Colima

**Symptom.** `./mvnw -B -f services/<svc>/pom.xml verify` fails because Testcontainers finds no Docker,
while `docker ps` works.

**Cause.** Testcontainers does not read `docker context`, and with Colima the socket and the path to mount
for Ryuk differ (`scripts/docker-env.sh`).

**Fix.** Once per shell:

```bash
source scripts/docker-env.sh
```

### Port already in use

**Symptom.** Compose fails to bind a port, or `kubectl port-forward` reports the address is in use.

**Cause.** The ports the two stacks open on the host:

| Stack | Ports |
|---|---|
| Compose (all on `127.0.0.1`) | 8443 gateway, 15672 RabbitMQ management, 8025 Mailpit, 4317/4318 OTel collector |
| Kubernetes NodePorts | 30443 gateway, 31672 RabbitMQ management, 30025 Mailpit |

They do not overlap, so Compose and the cluster can run side by side, but a `kubectl port-forward` to 8443,
15672 or 8025 clashes with a running Compose stack.

**Fix.** Find the owner and stop it, or forward to another local port:

```bash
lsof -nP -iTCP:8443 -sTCP:LISTEN
kubectl port-forward svc/api-gateway 9443:443
```
