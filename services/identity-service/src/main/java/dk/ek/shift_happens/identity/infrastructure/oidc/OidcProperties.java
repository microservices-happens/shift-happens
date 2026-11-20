package dk.ek.shift_happens.identity.infrastructure.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.oidc.*} in application.yml. Client ids and secrets come from OIDC_* environment
 * variables; the provider endpoints have the real defaults and are only overridden by tests.
 */
@ConfigurationProperties("app.oidc")
public record OidcProperties(
        String redirectBaseUrl,
        String frontendCallbackUrl,
        Duration stateTtl,
        Google google,
        GitHub github,
        Local local) {

    public record Google(String clientId, String clientSecret, String authorizationUri, String tokenUri,
                         String jwkSetUri, String issuer) {
    }

    public record GitHub(String clientId, String clientSecret, String authorizationUri, String tokenUri,
                         String userUri, String emailsUri) {
    }

    /**
     * A standard OpenID Connect provider with static endpoints: Keycloak locally, any OIDC provider in a
     * deployment. {@code issuer} is the value inside the ID token (what the browser-facing host produces,
     * e.g. http://localhost:8090/realms/shift-happens); the token and JWKS URIs may use the internal
     * service name instead, which is why they are configured separately.
     */
    public record Local(String clientId, String clientSecret, String authorizationUri, String tokenUri,
                        String jwkSetUri, String issuer) {
    }
}
