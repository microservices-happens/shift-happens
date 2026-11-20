# 0007 — A local OpenID Connect provider for the OAuth demo

Status: accepted (2026-11-20)

## Context
The course requires login through a third-party identity provider (OAuth 2.0 / OpenID Connect). Identity
already implements Google and GitHub with authorization code + PKCE, but both need a registered client id
and secret and real user accounts. That makes the flow impossible to demonstrate on a fresh clone, in CI,
or during an exam without external setup — and the demo is part of the delivery.

## Decision
Add a third provider, `local`, that speaks ordinary OpenID Connect against **Keycloak** in
`docker-compose.microservices.yml` (realm `shift-happens`, client `shift-happens-web`, public client with
PKCE S256, imported from `docker/microservices/local-oidc/realm.json`).

- The client code is a standard OIDC client (`LocalOidcClient`): authorization code + S256 verifier, token
  exchange, ID-token validation against the provider's JWKS with issuer and audience checks, then
  `email_verified` and the same e-mail matching as Google.
- The browser reaches Keycloak at `http://localhost:8090`; identity calls the token/JWKS endpoints at
  `http://local-oidc:8080`. The issuer in the token is the browser-facing URL, so it is configured
  separately (`OIDC_LOCAL_ISSUER`) from the endpoints it calls.
- The provider is disabled when `OIDC_LOCAL_CLIENT_ID` is empty, exactly like Google and GitHub; Compose
  sets it so the demo works out of the box.
- The demo users in the realm match the seeded workforce employees (`@shift-happens.example`), so OIDC
  login finds an existing active account instead of creating one.

## Alternatives considered
- **Only Google/GitHub.** Rejected: unreproducible demos and CI cannot exercise the flow.
- **A stub provider inside identity-service.** Rejected: it would not prove a real OIDC round trip (PKCE,
  discovery, signed ID token, JWKS).
- **A hand-written provider container.** Rejected: more code to maintain than importing a realm into a
  standard product.

## Consequences
- One more development container (~15 s start, ~400 MB image) and a second issuer to keep in sync with
  `OIDC_LOCAL_ISSUER`.
- Production can remove the container and leave the client id empty; Google/GitHub are unaffected because
  all providers share one code path.
- The report can present the local provider as the reproducible development identity provider and Google
  and GitHub as the production configuration.
