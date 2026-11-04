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

## Implemented so far (passwords and first admin, P2.4)

- `PUT /v1/auth/accounts/{employeeId}/password` → `204`. Allowed for an `Administrator`, or for the
  employee themself (the JWT's `employeeId` equals the path id); anyone else gets `403`. The role check
  comes before the lookup, so a non-admin gets `403` even for an unknown id; an admin gets `404`.
  A password shorter than 8 characters (or one bcrypt cannot take: more than 72 bytes with the pepper)
  is `400`. All errors are problem+json.
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

Still to do: Google/GitHub login (P2.3), JWT validation in the other services, and mounting the key
as a Kubernetes secret (P7.2).

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
- [x] Security tests: missing/expired/forged token → 401 (`LoginIT`); wrong role → 403 (`SetPasswordIT`).
