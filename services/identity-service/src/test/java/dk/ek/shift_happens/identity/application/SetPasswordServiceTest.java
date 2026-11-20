package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.domain.Caller;
import dk.ek.shift_happens.identity.infrastructure.security.PepperedPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Setting a password, without Spring or a database. */
class SetPasswordServiceTest {

    private final PasswordEncoder encoder = new PepperedPasswordEncoder("test-pepper");
    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final SetPasswordService service = new SetPasswordService(accounts, encoder);

    private void account(long id, boolean active) {
        accounts.save(new Account(id, id, "EMP-" + id, "e" + id + "@shift.dk", "Ada", "Lovelace", "Employee",
                active, 1, null));
    }

    @Test
    void theStoredHashIsThePepperedBcryptOfTheNewPassword() {
        account(7, true);

        service.setPassword(new Caller(7, false), 7, "correct horse");

        String hash = accounts.findByEmployeeId(7).orElseThrow().passwordHash();
        // Login checks with the same encoder, so this is what makes the new password work.
        assertThat(encoder.matches("correct horse", hash)).isTrue();
        assertThat(hash).doesNotContain("correct horse");
    }

    @Test
    void aNonAdminIsRefusedBeforeTheLookup() {
        // 403 for an unknown id too, so a non-admin cannot probe which employee ids have accounts.
        assertThatThrownBy(() -> service.setPassword(new Caller(7, false), 999, "correct horse"))
                .isInstanceOf(NotAllowedException.class);
    }

    @Test
    void anAdminGetsNotFoundForAnUnknownEmployee() {
        assertThatThrownBy(() -> service.setPassword(new Caller(1, true), 999, "correct horse"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void aDeactivatedAccountGetsThePasswordButStillCannotLogIn() {
        // An admin may prepare an account before workforce reactivates it; deactivation still wins.
        account(8, false);

        service.setPassword(new Caller(1, true), 8, "correct horse");

        Account account = accounts.findByEmployeeId(8).orElseThrow();
        assertThat(account.passwordHash()).isNotNull();
        assertThat(account.canLogInWithPassword()).isFalse();
    }

    private static final class InMemoryAccounts implements AccountRepository {
        private final Map<Long, Account> byEmployeeId = new HashMap<>();

        @Override
        public Optional<Account> findByEmployeeId(long employeeId) {
            return Optional.ofNullable(byEmployeeId.get(employeeId));
        }

        @Override
        public Optional<Account> findByEmail(String email) {
            return byEmployeeId.values().stream().filter(a -> a.email().equalsIgnoreCase(email)).findFirst();
        }

        @Override
        public boolean setPasswordHash(long employeeId, String passwordHash) {
            Account account = byEmployeeId.get(employeeId);
            if (account == null) {
                return false;
            }
            byEmployeeId.put(employeeId, account.withPasswordHash(passwordHash));
            return true;
        }

        @Override
        public boolean setPasswordHashIfUnset(long employeeId, String passwordHash) {
            Account account = byEmployeeId.get(employeeId);
            return account != null && account.passwordHash() == null && setPasswordHash(employeeId, passwordHash);
        }

        @Override
        public Account save(Account account) {
            byEmployeeId.put(account.employeeId(), account);
            return account;
        }
    }
}
