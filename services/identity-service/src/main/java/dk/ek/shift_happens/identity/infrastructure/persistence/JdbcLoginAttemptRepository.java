package dk.ek.shift_happens.identity.infrastructure.persistence;

import dk.ek.shift_happens.identity.domain.LoginAttempt;
import dk.ek.shift_happens.identity.domain.LoginAttemptRepository;
import dk.ek.shift_happens.identity.domain.OidcProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Adapter for oidc_login_attempt (V4__oidc.sql). Plain JDBC instead of JPA because single use needs
 * {@code DELETE ... RETURNING}: one statement that both reads and removes the row, so when two
 * callbacks race with the same state, Postgres lets exactly one of them get it.
 */
@Repository
class JdbcLoginAttemptRepository implements LoginAttemptRepository {

    private final JdbcTemplate jdbc;

    JdbcLoginAttemptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(LoginAttempt attempt) {
        jdbc.update("INSERT INTO oidc_login_attempt (state, provider, code_verifier, expires_at) VALUES (?, ?, ?, ?)",
                attempt.state(), attempt.provider().name(), attempt.codeVerifier(), utc(attempt.expiresAt()));
    }

    @Override
    public Optional<LoginAttempt> take(String state) {
        return jdbc.query("DELETE FROM oidc_login_attempt WHERE state = ? "
                                + "RETURNING state, provider, code_verifier, expires_at",
                        (rs, row) -> new LoginAttempt(rs.getString("state"), OidcProvider.valueOf(rs.getString("provider")),
                                rs.getString("code_verifier"), rs.getObject("expires_at", OffsetDateTime.class).toInstant()),
                        state)
                .stream()
                .findFirst();
    }

    @Override
    public void deleteExpired(Instant now) {
        jdbc.update("DELETE FROM oidc_login_attempt WHERE expires_at <= ?", utc(now));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
