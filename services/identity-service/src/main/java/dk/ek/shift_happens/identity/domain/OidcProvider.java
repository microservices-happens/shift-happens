package dk.ek.shift_happens.identity.domain;

import java.util.Arrays;
import java.util.Optional;

/** The third-party identity providers of contracts/openapi/identity.yaml, by their path segment. */
public enum OidcProvider {
    GOOGLE("google"),
    GITHUB("github");

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
