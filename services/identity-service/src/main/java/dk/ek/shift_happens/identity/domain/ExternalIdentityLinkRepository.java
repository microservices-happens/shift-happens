package dk.ek.shift_happens.identity.domain;

import java.util.Optional;

/** Port: which employee a provider account (provider + subject) belongs to. */
public interface ExternalIdentityLinkRepository {

    Optional<Long> findEmployeeId(OidcProvider provider, String subject);

    /** Idempotent: linking the same provider account again changes nothing. */
    void link(OidcProvider provider, String subject, long employeeId);
}
