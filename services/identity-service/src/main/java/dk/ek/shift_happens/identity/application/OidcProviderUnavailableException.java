package dk.ek.shift_happens.identity.application;

/** {@code authorize} for a provider that does not exist or has no client id configured: 400. */
public class OidcProviderUnavailableException extends RuntimeException {

    public OidcProviderUnavailableException() {
        super("This login provider is not available");
    }
}
