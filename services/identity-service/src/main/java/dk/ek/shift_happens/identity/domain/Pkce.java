package dk.ek.shift_happens.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * PKCE (RFC 7636) with the S256 method: the provider only sees the challenge in the browser redirect,
 * and only releases tokens to whoever later presents the matching verifier. A stolen authorization
 * code is therefore useless on its own.
 */
public final class Pkce {

    private Pkce() {
    }

    /** BASE64URL(SHA256(ASCII(verifier))) without padding. */
    public static String s256(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
