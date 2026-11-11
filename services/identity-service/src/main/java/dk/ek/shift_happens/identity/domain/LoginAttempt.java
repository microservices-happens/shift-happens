package dk.ek.shift_happens.identity.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * One started third-party login: the {@code state} sent to the provider, the PKCE verifier that stays
 * on our side, and when it stops being valid. It is stored in the database (not in a session) so any
 * replica can finish the login, and it is taken out of storage when used, so a state works once.
 */
public record LoginAttempt(String state, OidcProvider provider, String codeVerifier, Instant expiresAt) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static LoginAttempt start(OidcProvider provider, Instant now, Duration ttl) {
        return new LoginAttempt(randomToken(), provider, randomToken(), now.plus(ttl));
    }

    /** The value for {@code code_challenge} (method S256). */
    public String codeChallenge() {
        return Pkce.s256(codeVerifier);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** A state issued for Google must not complete a GitHub callback, and vice versa. */
    public boolean isFor(OidcProvider callbackProvider) {
        return provider == callbackProvider;
    }

    /** 32 random bytes, base64url: 43 characters, inside RFC 7636's 43-128 for a verifier. */
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
