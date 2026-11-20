package dk.ek.shift_happens.identity.application;

/**
 * A third-party login callback that does not end in a token. {@link #code()} is the short value sent
 * to the frontend as {@code #error=<code>}.
 */
public class OidcLoginException extends RuntimeException {

    public static final String UNKNOWN_PROVIDER = "unknown_provider";
    public static final String INVALID_STATE = "invalid_state";
    public static final String EXPIRED_STATE = "expired_state";
    public static final String ACCESS_DENIED = "access_denied";
    public static final String INVALID_REQUEST = "invalid_request";
    public static final String PROVIDER_ERROR = "provider_error";
    public static final String EMAIL_NOT_VERIFIED = "email_not_verified";
    /** Unknown and inactive accounts share one code, like the 401 of local login. */
    public static final String NO_ACTIVE_ACCOUNT = "no_active_account";

    private final String code;

    public OidcLoginException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
