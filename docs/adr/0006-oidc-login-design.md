# 0006. OIDC login: hand-written PKCE flow, e-mail matching, token in the URL fragment

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
The course requires third-party login. Identity must offer Google and GitHub next to local login and issue the same Shift Happens JWT for both. The contract (`contracts/openapi/identity.yaml`) fixes the paths `/v1/auth/oidc/{provider}/authorize|callback`, says that accounts are only created from workforce events, and that the callback redirects to the frontend with the JWT. The service is stateless and may run as several replicas. GitHub is OAuth 2.0 only and returns no ID token.

## Decision
Implemented in PR #30 (P2.3):
- **Flow:** authorization code with PKCE (S256), written by hand in identity-service. The `state` and the PKCE verifier are stored in Postgres (`oidc_login_attempt`), valid for 10 minutes and removed as they are read (`DELETE ... RETURNING`), so a state works once.
- **Who the user is:** for Google, the ID token is verified against Google's JWKS (signature, issuer, audience, expiry) and `email_verified` must be true. For GitHub, `GET /user` gives the id and `GET /user/emails` the primary e-mail, which must be verified.
- **Matching:** the first login matches the verified e-mail to an active account and stores a link (provider + subject) in `external_identity`. Later logins use the link, and the link wins over the e-mail. An account is never created by a login.
- **Result:** the callback always redirects to `FRONTEND_LOGIN_CALLBACK_URL`, with `#token=<JWT>` on success and `#error=<code>` otherwise. Unknown and inactive accounts give the same code.
- A provider without a client id is disabled.

## Consequences
- **Easier:** any replica can finish a login, because nothing is kept in an HTTP session. A stolen authorization code is useless without the verifier. The token is in the fragment, which browsers do not send to servers or put in `Referer` headers. A changed e-mail on either side does not lock the employee out.
- **Harder:** we own security code that a framework would otherwise provide, so it needs its own tests (`PkceTest`, `OidcLoginServiceTest`, `OidcIT`). A provider account stays bound to the employee it first matched. An employee whose provider e-mail differs from the workforce e-mail cannot use third-party login.
- The frontend must read the token from the fragment on `/login/callback` (P5.3, issue #58). This page does not exist yet.
- `GET /authorize` is unauthenticated and inserts a row per call. It needs rate limiting or a scheduled cleanup (follow-up from PR #30).
- A real login against Google or GitHub through the gateway has not been verified. The tests use stub providers.

## Alternatives considered
- Spring Security `oauth2Login` (named in `docs/architecture.md` §3): it needs an HTTP session and its own URL shapes, while the contract fixes the paths and the service is stateless.
- State and verifier in an HTTP session: only the replica that started the login could finish it.
- Matching on the e-mail at every login: an e-mail change on either side would lock the employee out.
- Creating the account at the first third-party login: accounts come only from workforce events, so an unknown e-mail is refused.
