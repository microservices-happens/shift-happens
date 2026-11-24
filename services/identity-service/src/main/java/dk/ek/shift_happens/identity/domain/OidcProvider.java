package dk.ek.shift_happens.identity.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * The third-party identity providers of contracts/openapi/identity.yaml, by their path segment.
 *
 * <p>{@link #LOCAL} is a real OpenID Connect provider too (Keycloak in docker-compose): it exists so the
 * authorization-code + PKCE flow can be demonstrated and tested without third-party accounts or secrets.
 * The code path is the same as Google's; only the endpoints and the client registration differ.
 */
public enum OidcProvider {
    GOOGLE("google"),
    GITHUB("github"),
    LOCAL("local");

    private final String id;

    OidcProvider(String id) {
        this.id = id;
    }

    /** The {@code {provider}} path segment, e.g. {@code google}. */
    public String id() {
        return id;
    }

    public static Optional<OidcProvider> fromId(String id) {
        return Arrays.stream(values()).filter(provider -> provider.id.equals(id)).findFirst();
    }
}
