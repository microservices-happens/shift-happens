package dk.ek.shift_happens.identity.application;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.infrastructure.security.PepperedPasswordEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The first-admin bootstrap rule, without Spring or a database. */
class AdminBootstrapTest {

    private static final String EMAIL = "root@shift.dk";
    private static final String PASSWORD = "bootstrap-secret";

    private final PasswordEncoder encoder = new PepperedPasswordEncoder("test-pepper");
    private final InMemoryAccounts accounts = new InMemoryAccounts();

    private AdminBootstrap bootstrap(String email, String password) {
        return new AdminBootstrap(accounts, encoder, email, password);
    }

    private static Account account(String email, String role, String passwordHash) {
        return new Account(1L, 1, "EMP-1", email, "Root", "Admin", role, true, 1, passwordHash);
    }

    @Test
    void theAdministratorWithTheEmailGetsThePassword() {
        // Email in any case, like login.
        Account result = bootstrap(EMAIL, PASSWORD).apply(account("Root@Shift.DK", "Administrator", null));

        assertThat(encoder.matches(PASSWORD, result.passwordHash())).isTrue();
    }

    @Test
    void aPasswordTooLongForBcryptStopsStartup() {
        // Otherwise the admin's workforce event would fail inside the projection and be dead-lettered,
        // so the admin account would silently never exist.
        assertThatThrownBy(() -> bootstrap(EMAIL, "x".repeat(70)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void anExistingPasswordIsNeverOverwritten() {
        // Otherwise every restart or workforce update would reset a password the admin has since changed.
        Account withPassword = account(EMAIL, "Administrator", "changed-hash");

        assertThat(bootstrap(EMAIL, PASSWORD).apply(withPassword).passwordHash()).isEqualTo("changed-hash");
    }

    @Test
    void aNonAdministratorWithTheEmailGetsNoPassword() {
        // A wrong email in the config must not hand a known password to an ordinary employee.
        assertThat(bootstrap(EMAIL, PASSWORD).apply(account(EMAIL, "Employee", null)).passwordHash()).isNull();
        assertThat(bootstrap(EMAIL, PASSWORD).apply(account(EMAIL, "Manager", null)).passwordHash()).isNull();
    }

    @Test
    void otherAccountsAreUntouched() {
        assertThat(bootstrap(EMAIL, PASSWORD).apply(account("other@shift.dk", "Administrator", null))
                .passwordHash()).isNull();
    }

    @Test
    void emptyVariablesTurnItOff() {
        Account admin = account(EMAIL, "Administrator", null);

        assertThat(bootstrap("", "").apply(admin).passwordHash()).isNull();
        assertThat(bootstrap(null, null).apply(admin).passwordHash()).isNull();
    }

    @Test
    void halfAConfigurationOrAShortPasswordFailsAtStartup() {
        // Fail loud instead of silently running without the bootstrap the operator asked for.
        assertThatThrownBy(() -> bootstrap(EMAIL, "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bootstrap("", PASSWORD)).isInstanceOf(IllegalStateException.class);
        // Same minimum as PUT /v1/auth/accounts/{id}/password.
        assertThatThrownBy(() -> bootstrap(EMAIL, "short")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anAdminThatArrivedBeforeStartupGetsThePasswordAtStartup() {
        accounts.save(account(EMAIL, "Administrator", null));

        bootstrap(EMAIL, PASSWORD).applyToExistingAccount();

        assertThat(encoder.matches(PASSWORD, accounts.findByEmployeeId(1).orElseThrow().passwordHash())).isTrue();
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
