package dk.ek.shift_happens.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void anAttemptExpiresAfterItsTtl() {
        // A state lying around in a browser history must not complete a login later.
        LoginAttempt attempt = LoginAttempt.start(OidcProvider.GOOGLE, NOW, Duration.ofMinutes(10));

        assertThat(attempt.isExpiredAt(NOW.plus(Duration.ofMinutes(9)))).isFalse();
        assertThat(attempt.isExpiredAt(NOW.plus(Duration.ofMinutes(10)))).isTrue();
    }

    @Test
    void stateAndVerifierAreUnguessableAndValidForPkce() {
        LoginAttempt first = LoginAttempt.start(OidcProvider.GITHUB, NOW, Duration.ofMinutes(10));
        LoginAttempt second = LoginAttempt.start(OidcProvider.GITHUB, NOW, Duration.ofMinutes(10));

        assertThat(first.state()).isNotEqualTo(second.state());
        assertThat(first.codeVerifier()).isNotEqualTo(second.codeVerifier()).isNotEqualTo(first.state());
        // RFC 7636: 43-128 characters from the unreserved set.
        assertThat(first.codeVerifier()).hasSizeBetween(43, 128).matches("[A-Za-z0-9._~-]+");
        assertThat(first.codeChallenge()).isEqualTo(Pkce.s256(first.codeVerifier()));
    }

    @Test
    void aStateOnlyCompletesTheProviderItWasIssuedFor() {
        LoginAttempt google = LoginAttempt.start(OidcProvider.GOOGLE, NOW, Duration.ofMinutes(10));

        assertThat(google.isFor(OidcProvider.GOOGLE)).isTrue();
        assertThat(google.isFor(OidcProvider.GITHUB)).isFalse();
    }
}
