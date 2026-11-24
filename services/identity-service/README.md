# identity-service

**Owns:** login accounts (email, bcrypt+pepper password hash, role, active flag), links to external identities (provider + subject), and the JWT signing key pair.
**Database:** `identity-db` (Postgres, Flyway). **Compose:** `identity-service`.

## Implemented so far (employee replica, P2.2)

- Consumes `workforce.employee.created/updated/deleted.v1` from the durable queue `identity.accounts`
  (bound to `workforce.employee.*.v1`). A message that fails three times (or cannot be converted) is
  republished to `shift-happens.dlx` with the queue name `identity.accounts` as routing key and lands in
  `identity.accounts.dlq`, which is bound with exactly that key, so it holds only identity's failures. It is
  never requeued forever. `DeadLetterTopologyIT` covers it.
- **Upgrading an existing broker needs no manual step.** Older versions bound `identity.accounts.dlq` to the
  DLX with `#` (it received every service's dead letters). `StaleDeadLetterBindingRemover` deletes that
  binding on every broker connection, after the topology is declared. The queue arguments are deliberately
  unchanged (only `x-dead-letter-exchange`; no `x-dead-letter-routing-key` as leave has), because RabbitMQ
  refuses to redeclare an existing queue with different arguments; the routing key comes from the retry
  recoverer, the path every failed message takes. Messages already in `identity.accounts` are kept.
  Messages already in `identity.accounts.dlq` are kept too, including foreign copies received through the
  old `#` binding: inspect and purge them (docs/wiki/Troubleshooting.md, "Messages pile up in a `.dlq`").
- Upserts the local `account` row, keeps the highest `aggregateVersion`, keeps tombstoned accounts
  as disabled, and de-duplicates on `eventId` (`processed_events`).
- The handler is commutative (P3.3): the same events give the same row in any arrival order.
  `snapshot_version` (V4) is the version of the snapshot the workforce fields come from, and
  `aggregate_version` the highest version seen. A tombstone newer than every snapshot
  (`aggregate_version > snapshot_version`) keeps the account disabled; a snapshot that arrives after
  it still refreshes the fields, and a tombstone that arrives before the create is stored as a row
  without email and role, so the late create cannot enable the account.
- Tests: `AccountTest` (version/tombstone rules), `EmployeeProjectionShuffledOrderTest` (every order
  of one employee's events, seeded shuffles with redeliveries across employees, same end state),
  `IdentityApplicationIT` (RabbitMQ → account table, including duplicate, out-of-order and tombstone
  cases), plus the system test `tests/cooperation/EmployeeReplicationIT`.

## Implemented so far (local login, P2.1)

- `POST /v1/auth/login`: email (any case) + password → RS256 JWT and the monolith's `LoginResponse`.
  Unknown email, wrong password, no password set and deactivated account all give the same `401`.
- `GET /.well-known/jwks.json`: the public signing key (never the private part).
- Passwords are `bcrypt(PASSWORD_PEPPER + password)`, ported from the monolith. The service refuses
  to start without `PASSWORD_PEPPER`. A workforce update never touches the hash.
- JWT claims: `sub` (employee id), `employeeId`, `role`, `iss`, `iat`, `exp` (`JWT_TTL`, default 24 h).
- Signing key: `JWT_PRIVATE_KEY_PATH` (PKCS#8 PEM). Without it a key is generated at startup, so
  tokens stop working after a restart; fine for Compose. In Kubernetes the key is mounted from
  `identity-service-secrets` at `/etc/identity-service/jwt/private-key.pem` (see `kubernetes/README.md`).
- Every other path requires a valid JWT from this service, except `/health`, `/error`, the OIDC
  `authorize`/`callback` and the Swagger UI paths (`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`,
  `/openapi/**`). A missing or invalid token gets Spring Security's default `401` (`WWW-Authenticate`
  header, no problem+json body).
- Migrations: `V1__init.sql`, `V2__login.sql`, `V3__oidc.sql`, `V4__account_snapshot_version.sql`.
- Tests: `LoginServiceTest`, `AccountTest`, `LoginIT` (login end to end; the token verifies with only
  the JWKS; missing, forged and expired tokens → 401).

## Implemented so far (passwords and first admin, P2.4)

- `PUT /v1/auth/accounts/{employeeId}/password` → `204`. Allowed for an `Administrator`, or for the
  employee themself (the JWT's `employeeId` equals the path id); anyone else gets `403`. The role check
  comes before the lookup, so a non-admin gets `403` even for an unknown id; an admin gets `404`.
  A password shorter than 8 characters (or one bcrypt cannot take: more than 72 bytes with the pepper)
  is `400`. These application errors are problem+json.
- The JWT `role` claim becomes the authority `ROLE_<role>` (`JwtAuthenticationConverter` in `SecurityConfig`).
- A deactivated account can get a password; it still cannot log in until workforce reactivates it.
- First-admin bootstrap: set `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` (min. 8 characters;
  both empty = off, only one set = the service does not start). When the account with that email
  arrives from workforce (or already exists at startup), has role `Administrator` and has no password
  yet, it gets that password. An existing password is never overwritten; a non-Administrator with that
  email is skipped with a warning in the log. That admin then sets everyone else's password.
- Tests: `CallerTest` (self / admin / other), `SetPasswordServiceTest`, `AdminBootstrapTest`,
  `SetPasswordIT` (own password then login, someone else's → 403, admin → 204, unknown → 404,
  short → 400, the bootstrap admin can log in after its workforce event).

Local demo accounts: identity has no seed of its own. `scripts/seed-dev.sh` seeds workforce, the
accounts arrive here through the normal `workforce.employee.*` events, and passwords are set with the
bootstrap admin and `PUT .../password` (see "Local demo data" in
[workforce-service/README.md](../workforce-service/README.md)).

## Implemented so far (Google/GitHub/local login, P2.3)

OAuth 2.0 authorization code with PKCE (S256), written by hand (no Spring `oauth2Login`, which needs
an HTTP session and its own URL shapes). Three providers use the same code path:

- **Google** and **GitHub** — the production third-party providers.
- **local** — a standard OpenID Connect provider (Keycloak in `docker-compose.microservices.yml`, realm
  `shift-happens`) that makes the flow demonstrable and testable without external accounts or secrets.
  Only the four endpoints and the client registration differ.

1. `GET /v1/auth/oidc/{google|github|local}/authorize` stores a random `state` and PKCE `code_verifier` in
   `oidc_login_attempt` (Postgres, valid 10 minutes, not an HTTP session, so any replica can take the
   callback) and answers `302` to the provider with `state` and `code_challenge`.
2. The provider sends the browser to `GET /v1/auth/oidc/{provider}/callback?code&state`. The state
   row is deleted as it is read (`DELETE ... RETURNING`), so a state works once.
3. The code is exchanged together with the verifier.
   - **Google and local** (`openid email`): the ID token is verified against the provider's JWKS (RS256,
     issuer, audience = our client id, expiry), and `email_verified` must be true.
   - **GitHub** (`user:email`, OAuth only): `GET /user` gives the stable id, `GET /user/emails` the
     primary email, which must be verified.
4. The account is found by its link in `external_identity` (provider + subject) if this provider
   account has logged in before, otherwise by the verified email (any case). The account must be
   active; no password is needed. On success the link is stored. **The link wins over the email**:
   an email change on either side does not lock the employee out, and a provider account stays bound
   to the employee it first matched.
5. `302` to `FRONTEND_LOGIN_CALLBACK_URL#token=<Shift Happens JWT>`, or `#error=<code>`. Codes:
   `invalid_state`, `expired_state`, `access_denied` (consent cancelled), `invalid_request`,
   `provider_error`, `email_not_verified`, `no_active_account` (unknown and inactive look the same),
   `unknown_provider`, `server_error`. No account is ever created here.

A provider without a client id is disabled: `authorize` answers `400` problem+json. Tokens, codes and
secrets are never logged.

| Variable | Meaning |
|---|---|
| `OIDC_REDIRECT_BASE_URL` | Browser-facing base of the callbacks, e.g. `https://localhost:8443/api/v1/auth/oidc` |
| `OIDC_GOOGLE_CLIENT_ID` / `OIDC_GOOGLE_CLIENT_SECRET` | Google OAuth client (empty = Google disabled) |
| `OIDC_GITHUB_CLIENT_ID` / `OIDC_GITHUB_CLIENT_SECRET` | GitHub OAuth app (empty = GitHub disabled) |
| `OIDC_LOCAL_CLIENT_ID` / `OIDC_LOCAL_CLIENT_SECRET` | Local OpenID Connect client (empty = local disabled; Compose sets `shift-happens-web`, a public PKCE client) |
| `OIDC_LOCAL_AUTHORIZATION_URI`, `OIDC_LOCAL_TOKEN_URI`, `OIDC_LOCAL_JWK_SET_URI`, `OIDC_LOCAL_ISSUER` | Endpoints of the local provider. The browser-facing issuer is `http://localhost:8090/realms/shift-happens`; identity calls Keycloak at `http://local-oidc:8080` inside Compose, which is why the URIs are separate |
| `FRONTEND_LOGIN_CALLBACK_URL` | Frontend page that reads `#token=` / `#error=` |

Registering the redirect URI (`<OIDC_REDIRECT_BASE_URL>/{google|github|local}/callback`):

- **Google:** Google Cloud Console → APIs & Services → Credentials → Create OAuth client ID → *Web
  application*. Authorized redirect URI: `https://localhost:8443/api/v1/auth/oidc/google/callback`
  (locally). Configure the consent screen with the `openid` and `email` scopes.
- **GitHub:** Settings → Developer settings → OAuth Apps → New OAuth App. Authorization callback URL:
  `https://localhost:8443/api/v1/auth/oidc/github/callback`.
- **Local (Keycloak):** already configured in `docker/microservices/local-oidc/realm.json`
  (`https://localhost:8443/api/v1/auth/oidc/local/callback`, public client, PKCE S256). The realm
  imports users `admin@shift-happens.example`, `sofie.jensen@shift-happens.example` and
  `ida.hansen@shift-happens.example` with the password `Demo1234!`; they match the demo employees, so
  no extra setup is needed after `scripts/seed-dev.sh`.

Put the ids and secrets in `.env` (Compose). In Kubernetes the client ids are plain env in
`kubernetes/identity-service/deployment.yaml` and the client secrets go in the git-ignored
`kubernetes/secrets/secrets.env`, applied by `kubernetes/secrets/create-secrets.sh`. The
provider endpoints are `app.oidc.*` properties with the real defaults; tests point them at a stub.

- Tests: `PkceTest` (RFC 7636 vector), `LoginAttemptTest` (expiry, randomness, provider binding),
  `AccountTest`, `OidcLoginServiceTest` (single-use and expired state, verified + active only, link
  wins over email), `OidcIT` (stub Google with a signed ID token and JWKS, stub GitHub API: redirects,
  PKCE verifier matches the challenge, happy paths, unknown/inactive/unverified, replayed state, forged
  ID token, link survives an email change), `OidcDisabledProviderIT` (no client id → 400).
- Not verified: a real login against Google or GitHub, through the gateway. The frontend page at
  `FRONTEND_LOGIN_CALLBACK_URL` is not part of this service (it is `frontend/src/pages/LoginCallbackPage.tsx`).

Still to do: a real Google/GitHub login through the gateway, and JWT validation in leave-service,
notification-service and audit-service (P1.7; workforce, scheduling and ai validate the token already).

## Provides
REST: [`contracts/openapi/identity.yaml`](../../contracts/openapi/identity.yaml)
- `POST /v1/auth/login`: local login. Same response shape as the monolith.
- `GET /v1/auth/oidc/{google|github}/authorize` and `/callback`: third-party login (OAuth 2.0 authorization code + PKCE). The provider's verified email must match an active account. Returns a Shift Happens JWT.
- `PUT /v1/auth/accounts/{employeeId}/password`
- `GET /.well-known/jwks.json`: public key for every other service (internal).

## Consumes
| Queue | Routing keys | Handling |
|---|---|---|
| `identity.accounts` | `workforce.employee.{created,updated,deleted}.v1` (one wildcard binding `workforce.employee.*.v1`) | Upsert the account (email, role, active = `employmentStatus == ACTIVE`). A tombstone disables it, also when it arrives before the create. Keeps the highest `aggregateVersion`; the result does not depend on arrival order |

## Publishes
Nothing.

## Correlation id (P1.6)

`config/CorrelationIdFilter` takes `X-Correlation-Id` from the request (a token of up to 128
`[A-Za-z0-9._:-]` characters) or generates a UUID, puts it in the MDC as `correlationId`, and echoes
it on the response. Every log line prints it (`logging.pattern.correlation`). Consumed `workforce.employee.*` events put their `correlationId` in the MDC while they are handled.

## Extract from monolith
`auth/*`: `AuthController`, `JwtService` (sign with RS256 and publish the public key as JWKS), `PepperedPasswordEncoder` and `CustomUserDetailsService`. Migrate `employee.login_password` into the accounts table.

## API docs (Swagger UI)

Swagger UI is at `http://localhost:8080/swagger-ui.html` (service port; through the gateway it is not routed). It renders
the hand-written contract `contracts/openapi/identity.yaml`, not a spec generated from the controllers. Maven copies
the file onto the classpath at build time (`/openapi/identity.yaml`); never edit the copy. The Docker build gets the file
through `--build-context contracts=contracts` (set in Compose and CI).

## Done when
- [ ] Local login and Google (or GitHub) login both work through the gateway. (Both work inside the
  service against stubs, `LoginIT` and `OidcIT`; a real provider through the gateway is not verified.)
- [x] A deactivated employee can no longer log in: `LoginIT.aDeactivatedEmployeeCanNoLongerLogIn` (an
  `employmentStatus` other than `ACTIVE` arrives from workforce, login is then `401`). A tombstone disables the
  account too (`IdentityApplicationIT`), but no test logs in after one.
- [x] Security tests: missing/expired/forged token → 401 (`LoginIT`); wrong role → 403 (`SetPasswordIT`).
