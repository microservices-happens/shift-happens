# identity-service

**Owns:** login accounts (email, bcrypt+pepper password hash, role, active flag), links to external identities (provider + subject), and the JWT signing key pair.
**Database:** `identity-db` (Postgres, Flyway). **Compose:** `identity-service`.

## Implemented so far (employee replica, P2.2)

- Consumes `workforce.employee.created/updated/deleted.v1` from the durable queue `identity.accounts`
  (bound to `workforce.employee.*.v1`, dead-lettered to `shift-happens.dlx` after three attempts).
- Upserts the local `account` row, keeps the highest `aggregateVersion`, keeps tombstoned accounts
  as disabled, and de-duplicates on `eventId` (`processed_events`).
- Tests: `AccountTest` (version/tombstone rules), `IdentityApplicationIT` (RabbitMQ → account table,
  including duplicate, out-of-order and tombstone cases), plus the system test
  `tests/cooperation/EmployeeReplicationIT`.

## Implemented so far (local login, P2.1)

- `POST /v1/auth/login`: email (any case) + password → RS256 JWT and the monolith's `LoginResponse`.
  Unknown email, wrong password, no password set and deactivated account all give the same `401`.
- `GET /.well-known/jwks.json`: the public signing key (never the private part).
- Passwords are `bcrypt(PASSWORD_PEPPER + password)`, ported from the monolith. The service refuses
  to start without `PASSWORD_PEPPER`. A workforce update never touches the hash.
- JWT claims: `sub` (employee id), `employeeId`, `role`, `iss`, `iat`, `exp` (`JWT_TTL`, default 24 h).
- Signing key: `JWT_PRIVATE_KEY_PATH` (PKCS#8 PEM). Without it a key is generated at startup, so
  tokens stop working after a restart; fine for Compose, not for Kubernetes.
- Every other path requires a valid JWT from this service.
- Tests: `LoginServiceTest`, `AccountTest`, `LoginIT` (login end to end; the token verifies with only
  the JWKS; missing, forged and expired tokens → 401).

## Implemented so far (Google/GitHub login, P2.3)

OAuth 2.0 authorization code with PKCE (S256), written by hand (no Spring `oauth2Login`, which needs
an HTTP session and its own URL shapes).

1. `GET /v1/auth/oidc/{google|github}/authorize` stores a random `state` and PKCE `code_verifier` in
   `oidc_login_attempt` (Postgres, valid 10 minutes, not an HTTP session, so any replica can take the
   callback) and answers `302` to the provider with `state` and `code_challenge`.
2. The provider sends the browser to `GET /v1/auth/oidc/{provider}/callback?code&state`. The state
   row is deleted as it is read (`DELETE ... RETURNING`), so a state works once.
3. The code is exchanged together with the verifier.
   - **Google** (`openid email`): the ID token is verified against Google's JWKS (RS256, issuer,
     audience = our client id, expiry), and `email_verified` must be true.
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
| `FRONTEND_LOGIN_CALLBACK_URL` | Frontend page that reads `#token=` / `#error=` |

Registering the redirect URI (`<OIDC_REDIRECT_BASE_URL>/{google|github}/callback`):

- **Google:** Google Cloud Console → APIs & Services → Credentials → Create OAuth client ID → *Web
  application*. Authorized redirect URI: `https://localhost:8443/api/v1/auth/oidc/google/callback`
  (locally). Configure the consent screen with the `openid` and `email` scopes.
- **GitHub:** Settings → Developer settings → OAuth Apps → New OAuth App. Authorization callback URL:
  `https://localhost:8443/api/v1/auth/oidc/github/callback`.

Put the ids and secrets in `.env` (Compose) or `kubernetes/shared/secret.yaml` (Kubernetes). The
provider endpoints are `app.oidc.*` properties with the real defaults; tests point them at a stub.

- Tests: `PkceTest` (RFC 7636 vector), `LoginAttemptTest` (expiry, randomness, provider binding),
  `AccountTest`, `OidcLoginServiceTest` (single-use and expired state, verified + active only, link
  wins over email), `OidcIT` (stub Google with a signed ID token and JWKS, stub GitHub API: redirects,
  PKCE verifier matches the challenge, happy paths, unknown/inactive/unverified, replayed state, forged
  ID token, link survives an email change), `OidcDisabledProviderIT` (no client id → 400).
- Not verified: a real login against Google or GitHub, through the gateway. The frontend page at
  `FRONTEND_LOGIN_CALLBACK_URL` is not part of this service.

Still to do: `PUT /v1/auth/accounts/{id}/password` and a first-admin bootstrap (P2.4), JWT validation in the other services, and mounting the key as a Kubernetes secret (P7.2).
Until P2.4 nobody has a password, so login only works in tests.

## Provides
REST: [`contracts/openapi/identity.yaml`](../../contracts/openapi/identity.yaml)
- `POST /v1/auth/login`: local login. Same response shape as the monolith.
- `GET /v1/auth/oidc/{google|github}/authorize` and `/callback`: third-party login (OAuth 2.0 authorization code + PKCE). The provider's verified email must match an active account. Returns a Shift Happens JWT.
- `PUT /v1/auth/accounts/{employeeId}/password`
- `GET /.well-known/jwks.json`: public key for every other service (internal).

## Consumes
| Queue | Routing keys | Handling |
|---|---|---|
| `identity.accounts` | `workforce.employee.{created,updated,deleted}.v1` | Upsert the account (email, role, active = `employmentStatus == ACTIVE`). A tombstone disables it. Keeps the highest `aggregateVersion` |

## Publishes
Nothing.

## Extract from monolith
`auth/*`: `AuthController`, `JwtService` (sign with RS256 and publish the public key as JWKS), `PepperedPasswordEncoder` and `CustomUserDetailsService`. Migrate `employee.login_password` into the accounts table.

## Done when
- [ ] Local login and Google (or GitHub) login both work through the gateway. (Both work inside the
  service against stubs, `LoginIT` and `OidcIT`; a real provider through the gateway is not verified.)
- [ ] A deactivated employee can no longer log in.
- [ ] Security tests: missing/expired/forged token → 401 (done); wrong role → 403.
