package dk.ek.shift_happens.identity.domain;

import java.util.Optional;

/** Port: the projection logic does not know that the data lives in PostgreSQL. */
public interface AccountRepository {

    Optional<Account> findByEmployeeId(long employeeId);

    /** Case-insensitive, because people type their email however they like. */
    Optional<Account> findByEmail(String email);

    Account save(Account account);
}
