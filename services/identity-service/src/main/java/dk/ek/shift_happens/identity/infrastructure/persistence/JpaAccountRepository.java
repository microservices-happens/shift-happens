package dk.ek.shift_happens.identity.infrastructure.persistence;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: upsert by employeeId, so the projection is naturally idempotent. */
@Repository
class JpaAccountRepository implements AccountRepository {

    private final AccountJpaRepository jpa;

    JpaAccountRepository(AccountJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Account> findByEmployeeId(long employeeId) {
        return jpa.findByEmployeeId(employeeId).map(AccountEntity::toDomain);
    }

    @Override
    public Account save(Account account) {
        AccountEntity entity = jpa.findByEmployeeId(account.employeeId()).orElseGet(AccountEntity::new);
        entity.apply(account);
        return jpa.save(entity).toDomain();
    }
}
