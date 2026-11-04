package dk.ek.shift_happens.identity.application;

/**
 * A provider call failed or returned something that does not verify. The message is for the log and
 * must never contain a code, token or secret.
 */
public class OidcProviderException extends RuntimeException {

    public OidcProviderException(String message) {
        super(message);
    }
}
