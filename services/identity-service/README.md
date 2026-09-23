# identity-service

**Owns:** login accounts (email, bcrypt+pepper password hash, role, active flag) and the JWT signing key pair.
**Database:** `identity-db` (Postgres). **Compose:** `identity-service`.

## Provides
- REST: [`contracts/openapi/identity.yaml`](../../contracts/openapi/identity.yaml)
  - `POST /auth/login` returns the same response as the monolith, so the frontend is unchanged.
  - `PUT /auth/accounts/{employeeId}/password`
  - `GET /.well-known/jwks.json` (internal: every service verifies JWTs with it)

## Consumes
| Queue | Binding | Handling |
|---|---|---|
| `identity.accounts` | `workforce.employee.#` | Upsert the account for `employeeId`: email, role, and active = `employmentStatus == ACTIVE` |

## Publishes
Nothing.

## Extract from monolith
`auth/*`: `AuthController`, `JwtService` (sign with RS256 and publish the public key as JWKS), `PepperedPasswordEncoder` and `CustomUserDetailsService`. Migrate `employee.login_password` into the accounts table.

## Done when
- [ ] Login works through the gateway and the existing frontend.
- [ ] A new employee created in workforce can log in after an admin sets a password.
- [ ] A deactivated employee can no longer log in.
