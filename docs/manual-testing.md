# Manual testing guide

How to log in and exercise the running stack by hand. It assumes the Compose stack from the root README
(`docker compose -f docker-compose.microservices.yml --profile app up -d --build`) and the demo data
(`scripts/seed-dev.sh`).

## 1. What is where

| What | URL | Notes |
|---|---|---|
| Frontend (through the gateway) | <https://localhost:8443> | Caddy `tls internal`: accept the certificate warning once |
| REST API | `https://localhost:8443/api/v1/...` | The gateway strips `/api` and routes by resource |
| GraphQL | `https://localhost:8443/api/graphql` | scheduling-query's read model |
| Keycloak (local OIDC provider) | <http://localhost:8090> | realm `shift-happens`; admin console `admin` / `admin` |
| Mailpit (captured e-mails) | <http://localhost:8025> | one message per notification with an address |
| RabbitMQ management | <http://localhost:15672> | `shift` / `shift_dev` |
| Grafana (live dashboard) | <http://localhost:3001> | `admin` / `admin` unless `GRAFANA_PASSWORD` is set; **Shift Happens — Live System** |
| Prometheus (PromQL) | <http://localhost:9090> | targets, metrics, alerts |
| Loki (logs) | query through Grafana Explore | label `service` = Compose service name |
| Swagger / GraphiQL, direct ports | `http://localhost:8081`–`8089` | only with `docker-compose.ports.yml` (section 4) |

The Java services publish no ports by default; everything reaches them through the gateway. That is the
deployment model, not an accident.

## 2. Logging in

All **active** demo accounts use the password **`Demo1234!`** (the inactive employee 12 has no password).
There are two ways in:

| Way | Who | How |
|---|---|---|
| Local e-mail + password | every seeded account | `admin@shift-happens.example` (Administrator), `sofie.jensen@shift-happens.example` (Manager), `ida.hansen@shift-happens.example` (Employee), … |
| **Local demo (Keycloak)** button | `admin@`, `sofie.jensen@`, `ida.hansen@` (the realm users) | real OIDC authorization code + PKCE against `localhost:8090`; same password |

The Google and GitHub buttons are disabled unless `OIDC_GOOGLE_CLIENT_ID` / `OIDC_GITHUB_CLIENT_ID` are
set; the local provider is the reproducible one.

Roles decide the UI: an Employee sees Dashboard, My Schedule, Shifts, Shift Assignments and Leave
Requests; a Manager also sees Employees and Departments; only an Administrator sees **Audit Log**.

## 3. Things to click

| Flow | Where | What to look for |
|---|---|---|
| Schedule | **Shifts** / **My Schedule** | Data comes from scheduling-query over GraphQL (`POST /api/graphql` in DevTools, not one REST call per widget). **New shift** starts with one required job role; add/remove rows for the staffing plan |
| Employee | **Employees → New** | After **Create** the page stays on the employee so **Job roles** can be added (e.g. Sygeplejerske). Without a role Scheduling refuses the assignment |
| Assignment | **Shift Assignments → New** | An assignment publishes `scheduling.assignment.created.v1`; the bell gains a notification; Mailpit gets an e-mail if the employee replica has an address |
| Swap | **Shift Assignments → Request swap**, then **Swap Requests** as a Manager | The request is `PENDING`; **Approve** moves the assignment to the colleague (`SWAPPED`), **Reject** leaves it unchanged; both publish `scheduling.swap.*` |
| Understaffed + AI | **Shifts** with a released/cancelled assignment | `UnderstaffedShiftsPanel` shows ranked suggestions (`source: llm` or `rule`) |
| Leave types | **Leave Types** (Manager/Administrator) | Create a type, then **Grant days** (an `ACCRUAL` ledger entry). A new type has no balance, so approval would be a `409` |
| Leave + saga | **Leave Requests → New**, then approve as a Manager | Request goes `PENDING → APPROVAL_PENDING → APPROVED`; the overlapping assignment becomes `RELEASED_FOR_LEAVE`; a locked shift gives `REJECTED` with a reason |
| Notifications | Bell in the header | Polls `GET /api/v1/notifications`; clicking marks one read (`PATCH`) |
| Audit | **Audit Log** (Administrator) | Filter by `correlationId` to see a whole saga; expand an envelope |
| E-mail | Mailpit | `notification.email.requested.v1` → `email.send` → SMTP |

Notifications only get an e-mail once notification-service has an employee replica (an active e-mail for
that employee). The replica is built from `workforce.employee.*`; on a fresh stack it fills as employees
are created/updated, so the first event may be in-app only.

## 4. Calling the APIs by hand

### 4.1 Swagger UI and GraphiQL without the gateway

The services' Swagger UI is served inside each container. Publish the ports with the optional override:

```bash
docker compose -f docker-compose.microservices.yml -f docker-compose.ports.yml --profile app up -d
```

| Service | Swagger UI | OpenAPI file |
|---|---|---|
| identity | <http://localhost:8081/swagger-ui.html> | `/openapi/identity.yaml` |
| workforce | <http://localhost:8082/swagger-ui.html> | `/openapi/workforce.yaml` |
| scheduling-command | <http://localhost:8083/swagger-ui.html> | `/openapi/scheduling.yaml` |
| scheduling-query | <http://localhost:8084/graphiql> | GraphQL SDL in `contracts/graphql` |
| leave-command | <http://localhost:8085/swagger-ui.html> | `/openapi/leave.yaml` |
| leave-query | <http://localhost:8086/swagger-ui.html> | reads (GET) |
| notification | <http://localhost:8088/swagger-ui.html> | `/openapi/notification.yaml` |
| audit | <http://localhost:8089/swagger-ui.html> | `/openapi/audit.yaml` |

These ports bypass the gateway but not security: every call except `/health` and Swagger itself needs a
Bearer token. Starting the override recreates the services; identity generates a new signing key, so log in
again afterwards (the other services refetch the JWKS automatically).

### 4.2 Get a token (PowerShell)

```powershell
$login = Invoke-RestMethod -SkipCertificateCheck -Method Post `
  'https://localhost:8443/api/v1/auth/login' -ContentType 'application/json' `
  -Body '{"email":"sofie.jensen@shift-happens.example","password":"Demo1234!"}'
$token = $login.token
$login.roleName    # Manager
```

`curl` works too (`-k` for the self-signed certificate):

```bash
TOKEN=$(curl -sk -X POST https://localhost:8443/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"sofie.jensen@shift-happens.example","password":"Demo1234!"}' | jq -r .token)
```

### 4.3 Read and write

```powershell
# Any authenticated caller
Invoke-RestMethod -SkipCertificateCheck -Headers @{Authorization="Bearer $token"} `
  'https://localhost:8443/api/v1/employees'

# A write needs Administrator/Manager and an Idempotency-Key per user action
Invoke-RestMethod -SkipCertificateCheck -Method Post `
  -Headers @{Authorization="Bearer $token"; 'Idempotency-Key'=[guid]::NewGuid().ToString()} `
  -ContentType 'application/json' `
  -Body '{"employeeId":2,"leaveTypeId":1,"startDate":"2031-06-02","endDate":"2031-06-03","reason":"manual test"}' `
  'https://localhost:8443/api/v1/leaverequests'
```

Repeat the same `Idempotency-Key` with the same body and you get the stored response (no second request);
change the body and the service answers `409`. The frontend generates one key per user action.

GraphQL (`shifts` requires a window; the same query the Shifts page sends):

```powershell
$body = @{ query = 'query($from: DateTime!, $to: DateTime!) { shifts(from: $from, to: $to) { shiftId shiftName startDatetime status } }';
           variables = @{ from = '2031-01-01T00:00:00Z'; to = '2031-12-31T23:59:59Z' } } | ConvertTo-Json
Invoke-RestMethod -SkipCertificateCheck -Method Post `
  -Headers @{Authorization="Bearer $token"} -ContentType 'application/json' `
  -Body $body 'https://localhost:8443/api/graphql'
```

Without a token the endpoint answers `401`; an Employee may call `employeeSchedule` only for themselves.

Audit (Administrator token only):

```powershell
$admin = Invoke-RestMethod -SkipCertificateCheck -Method Post `
  'https://localhost:8443/api/v1/auth/login' -ContentType 'application/json' `
  -Body '{"email":"admin@shift-happens.example","password":"Demo1234!"}'
Invoke-RestMethod -SkipCertificateCheck -Headers @{Authorization="Bearer $($admin.token)"} `
  'https://localhost:8443/api/v1/auditlogs?correlationId=<id-from-a-notification>'
```

The same call as a Manager returns `403` problem+json; without a token it is `401`.


## 5. Looking inside

```bash
# Queue depth / dead letters (the web UI is easier: http://localhost:15672)
docker compose -f docker-compose.microservices.yml exec rabbitmq rabbitmqctl list_queues name messages consumers

# A service's log with the correlation id of each request/event
docker compose -f docker-compose.microservices.yml logs -f notification-service audit-service

# Direct SQL (read-only curiosity; no service is allowed to read another service's database)
docker compose -f docker-compose.microservices.yml exec notification-db \
  psql -U notification -d notification -c 'select notification_id,employee_id,title,is_read from notification order by 1 desc limit 10'

docker compose -f docker-compose.microservices.yml exec audit-db \
  psql -U audit -d audit -c 'select event_type, count(*) from audit_entry group by 1 order by 2 desc'
```

## 6. Resetting

```bash
# stop, keep data
docker compose -f docker-compose.microservices.yml --profile app down
# stop and wipe every database, read model and the Keycloak realm state
docker compose -f docker-compose.microservices.yml --profile app down -v
# start and seed again
docker compose -f docker-compose.microservices.yml --profile app up -d
scripts/seed-dev.sh
```

## 7. Common snags

| Symptom | Cause / fix |
|---|---|
| Browser warns about the certificate | Caddy uses a local CA; accept it once per browser |
| `401` after an identity restart | Tokens signed with the previous generated key; log in again. Set `JWT_PRIVATE_KEY_PATH` for a persistent key |
| `403` on a write | The token's role is Employee; use an Administrator or Manager account |
| No e-mail in Mailpit | No active employee replica with an address yet, or the event was consumed before the workforce event; update the employee and trigger another event |
| Notification bell empty | Notifications are per employee: log in as the employee the event concerns |
| Swagger 404 on a service port | The override file is not in the `-f` list, or the service was recreated without it |
| Audit page refuses | It is Administrator only; the nav item is hidden for other roles and the page does not call the API |
| Frontend healthcheck red | Fixed in this delivery: the healthcheck now uses `127.0.0.1` (busybox wget preferred `::1`, nginx listens on IPv4) |

## 8. Automated browser tests (Playwright)

`frontend/e2e/business-flows.spec.ts` walks the same flows over the real stack, so it needs the full
Compose profile running (not a mock):

```powershell
docker compose -f docker-compose.microservices.yml --profile app up -d
npm --prefix frontend run e2e           # headed: npm --prefix frontend run e2e:headed
npm --prefix frontend run e2e:report    # HTML report after a failure
```

The suite logs in as `sofie.jensen@shift-happens.example`, then:

1. reloads the page and proves the session survives (regression: the API client used to send no bearer
   token before the auth provider configured it, so a refresh logged the user out);
2. creates an employee and adds the Sygeplejerske job role;
3. creates a shift with a required job role and assigns that employee;
4. requests a swap to a freshly created target employee from the assignment list and approves it on **Swap Requests**;
5. creates a second shift and swap, rejects it, and checks the assignment stayed with the employee;
6. creates a leave type, grants 25 days, submits a request and approves it (the saga releases shifts);
7. submits a second request and rejects it with a reason.

It uses the locally installed Chrome by default; `E2E_BROWSER_CHANNEL=chromium` plus
`npx --prefix frontend playwright install chromium` uses the bundled browser instead. The tests create
uniquely named data (timestamp suffix) and use far-future days, so repeated runs do not collide.

`scripts/api-smoke.mjs` is the API-only equivalent when a browser is not available:
`node scripts/api-smoke.mjs` drives login, shift, assignment, swap approval, leave type, accrual,
leave request/approval and an audit read.

For a load run against the query role, with one or two replicas:

```powershell
docker compose -f docker-compose.microservices.yml --profile app up -d --scale scheduling-query=2
$env:LOAD_REQUESTS='10000'; $env:LOAD_CONCURRENCY='50'; node scripts/load-schedule.mjs
docker stats --no-stream
```

The captured crash/recovery, saga compensation, DLQ, Grafana stress and KEDA evidence is in
[docs/diagrams/observability-dlq-recovery.md](diagrams/observability-dlq-recovery.md#6-captured-evidence-2026-11-22-local-compose).

For the KEDA ScaledJob demo, port-forward the cluster RabbitMQ and publish a burst:

```powershell
kubectl -n shift-happens port-forward svc/rabbitmq 15672:15672
node scripts/keda-email-burst.mjs 500
kubectl -n shift-happens get jobs
```

The live Grafana dashboard is <http://localhost:3001/d/shift-happens-live>; Prometheus is
<http://localhost:9090>.

