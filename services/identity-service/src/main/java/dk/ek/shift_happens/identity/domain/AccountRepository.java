package dk.ek.shift_happens.identity.domain;

import java.util.Optional;

/** Port: the projection logic does not know that the data lives in PostgreSQL. */
public interface AccountRepository {

    Optional<Account> findByEmployeeId(long employeeId);

    /** Case-insensitive, because people type their email however they like. */
    Optional<Account> findByEmail(String email);

    /** Saves workforce-owned fields. Never changes the password hash of an existing account. */
    Account save(Account account);

    /**
     * Changes only the password hash, so a concurrent workforce update (e.g. a deactivation) is never
     * overwritten by stale data. Returns false if the account does not exist.
     */
    boolean setPasswordHash(long employeeId, String passwordHash);

    /** Like {@link #setPasswordHash}, but only while no password is set; atomic in the database. */
    boolean setPasswordHashIfUnset(long employeeId, String passwordHash);
}
