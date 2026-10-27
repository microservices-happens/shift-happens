package dk.ek.shift_happens.identity.domain;

import java.util.Optional;

/** Port: the projection logic does not know that the data lives in PostgreSQL. */
public interface AccountRepository {

    Optional<Account> findByEmployeeId(long employeeId);

    Account save(Account account);
}
