package dk.ek.shift_happens.identity.infrastructure.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * bcrypt(pepper + password), ported from the monolith (auth/PepperedPasswordEncoder). The pepper is a
 * server-side secret (PASSWORD_PEPPER), so a leaked database alone is not enough to crack the hashes.
 */
public class PepperedPasswordEncoder implements PasswordEncoder {

    private final String pepper;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public PepperedPasswordEncoder(String pepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException("PASSWORD_PEPPER must be set");
        }
        this.pepper = pepper;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return bcrypt.encode(pepper + rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return bcrypt.matches(pepper + rawPassword, encodedPassword);
    }
}
