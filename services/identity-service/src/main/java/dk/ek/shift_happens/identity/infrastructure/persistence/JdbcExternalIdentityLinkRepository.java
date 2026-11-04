package dk.ek.shift_happens.identity.infrastructure.persistence;

import dk.ek.shift_happens.identity.domain.ExternalIdentityLinkRepository;
import dk.ek.shift_happens.identity.domain.OidcProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/** Adapter for external_identity (V3__oidc.sql), JDBC like its sibling {@link JdbcLoginAttemptRepository}. */
@Repository
class JdbcExternalIdentityLinkRepository implements ExternalIdentityLinkRepository {

    private final JdbcTemplate jdbc;

    JdbcExternalIdentityLinkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> findEmployeeId(OidcProvider provider, String subject) {
        return jdbc.queryForList("SELECT employee_id FROM external_identity WHERE provider = ? AND subject = ?",
                        Long.class, provider.name(), subject)
                .stream()
                .findFirst();
    }

    @Override
    public void link(OidcProvider provider, String subject, long employeeId) {
        jdbc.update("INSERT INTO external_identity (provider, subject, employee_id, linked_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (provider, subject) DO NOTHING",
                provider.name(), subject, employeeId, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
