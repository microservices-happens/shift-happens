# identity-service

**Owns:** login accounts (email, bcrypt+pepper password hash, role, active flag), links to external identities (provider + subject), and the JWT signing key pair.
**Database:** `identity-db` (Postgres, Flyway). **Compose:** `identity-service`.

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
- [ ] Security tests: missing/expired/forged token → 401; wrong role → 403.
