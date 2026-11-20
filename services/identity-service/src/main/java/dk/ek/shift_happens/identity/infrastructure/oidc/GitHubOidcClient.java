package dk.ek.shift_happens.identity.infrastructure.oidc;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.ek.shift_happens.identity.application.OidcProviderClient;
import dk.ek.shift_happens.identity.application.OidcProviderException;
import dk.ek.shift_happens.identity.domain.ExternalIdentity;
import dk.ek.shift_happens.identity.domain.OidcProvider;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Arrays;
import java.util.Optional;

/**
 * GitHub is OAuth 2.0 only (no ID token), so the identity comes from its API with the access token:
 * {@code GET /user} for the stable numeric id and {@code GET /user/emails} for the primary email and
 * whether GitHub has verified it.
 */
public class GitHubOidcClient implements OidcProviderClient {

    private static final MediaType GITHUB_JSON = MediaType.parseMediaType("application/vnd.github+json");

    private final OidcProperties.GitHub properties;
    private final RestClient http;

    public GitHubOidcClient(OidcProperties.GitHub properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public OidcProvider provider() {
        return OidcProvider.GITHUB;
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
                .queryParam("scope", "user:email")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .encode()
                .build()
                .toUri();
    }

    @Override
    public ExternalIdentity exchange(String code, String codeVerifier, String redirectUri) {
        try {
            String accessToken = accessToken(code, codeVerifier, redirectUri);
            User user = http.get().uri(properties.userUri())
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(GITHUB_JSON)
                    .retrieve()
                    .body(User.class);
            Email[] emails = http.get().uri(properties.emailsUri())
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(GITHUB_JSON)
                    .retrieve()
                    .body(Email[].class);
            if (user == null || user.id() == null) {
                throw new OidcProviderException("GitHub user response has no id");
            }

            // Only the primary email counts, and only if GitHub has verified it.
            Optional<Email> primary = Arrays.stream(emails == null ? new Email[0] : emails)
                    .filter(Email::primary)
                    .findFirst();
            return new ExternalIdentity(OidcProvider.GITHUB, user.id().toString(),
                    primary.map(Email::email).orElse(null), primary.map(Email::verified).orElse(false));
        } catch (RestClientException e) {
            // Only the exception type: a response body could echo request values.
            throw new OidcProviderException("GitHub API call failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    private String accessToken(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);

        TokenResponse response = http.post().uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        // GitHub answers a bad code with 200 and {"error": ...}.
        if (response == null || response.accessToken() == null) {
            throw new OidcProviderException("GitHub token endpoint returned "
                    + (response == null ? "nothing" : response.error()));
        }
        return response.accessToken();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken, String error) {
    }

    private record User(Long id) {
    }

    private record Email(String email, boolean primary, boolean verified) {
    }
}
