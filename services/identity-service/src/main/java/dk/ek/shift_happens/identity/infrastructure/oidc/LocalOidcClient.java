package dk.ek.shift_happens.identity.infrastructure.oidc;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.ek.shift_happens.identity.application.OidcProviderClient;
import dk.ek.shift_happens.identity.application.OidcProviderException;
import dk.ek.shift_happens.identity.domain.ExternalIdentity;
import dk.ek.shift_happens.identity.domain.OidcProvider;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * A standard OpenID Connect provider behind the same authorization-code + PKCE flow as Google: the ID
 * token from the token endpoint is verified (RS256 against the provider's JWKS, issuer, audience = our
 * client id, expiry) before {@code sub}, {@code email} and {@code email_verified} are trusted.
 *
 * <p>docker-compose runs Keycloak as this provider, so the login can be demonstrated locally; the same
 * client also works against any other OIDC provider by changing the four endpoints in {@code app.oidc.local}.
 */
public class LocalOidcClient implements OidcProviderClient {

    private final OidcProperties.Local properties;
    private final RestClient http;
    private final JwtDecoder idTokenDecoder;

    public LocalOidcClient(OidcProperties.Local properties, RestClient http, RestOperations jwksHttp) {
        this.properties = properties;
        this.http = http;
        this.idTokenDecoder = idTokenDecoder(properties, jwksHttp);
    }

    @Override
    public OidcProvider provider() {
        return OidcProvider.LOCAL;
    }

    @Override
    public boolean enabled() {
        return properties.clientId() != null && !properties.clientId().isBlank();
    }

    @Override
    public URI authorizationUri(String state, String codeChallenge, String redirectUri) {
        return UriComponentsBuilder.fromUriString(properties.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
    }

    @Override
    public ExternalIdentity exchange(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", properties.clientId());
        if (properties.clientSecret() != null && !properties.clientSecret().isBlank()) {
            form.add("client_secret", properties.clientSecret());
        }
        form.add("code_verifier", codeVerifier);

        TokenResponse response;
        try {
            response = http.post().uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException e) {
            // Only the exception type: a response body could echo request values.
            throw new OidcProviderException("Local provider token endpoint failed ("
                    + e.getClass().getSimpleName() + ")");
        }
        if (response == null || response.idToken() == null) {
            throw new OidcProviderException("Local provider token response has no id_token");
        }

        Jwt idToken;
        try {
            idToken = idTokenDecoder.decode(response.idToken());
        } catch (JwtException e) {
            throw new OidcProviderException("Local provider ID token rejected (" + e.getClass().getSimpleName() + ")");
        }
        Object verified = idToken.getClaim("email_verified");
        return new ExternalIdentity(OidcProvider.LOCAL, idToken.getSubject(), idToken.getClaimAsString("email"),
                Boolean.TRUE.equals(verified) || "true".equals(verified));
    }

    private static JwtDecoder idTokenDecoder(OidcProperties.Local properties, RestOperations jwksHttp) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(jwksHttp)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                        iss -> iss != null && properties.issuer().equals(iss.toString())),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.clientId()))));
        return decoder;
    }

    private record TokenResponse(@JsonProperty("id_token") String idToken) {
    }
}
