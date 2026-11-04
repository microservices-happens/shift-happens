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

Still to do: `PUT /v1/auth/accounts/{id}/password` and a first-admin bootstrap (P2.4), Google/GitHub
login (P2.3), JWT validation in the other services, and mounting the key as a Kubernetes secret (P7.2).
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
- [ ] Local login and Google (or GitHub) login both work through the gateway.
- [ ] A deactivated employee can no longer log in.
- [ ] Security tests: missing/expired/forged token → 401 (done); wrong role → 403.
