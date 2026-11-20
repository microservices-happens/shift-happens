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

/** Who may log in, without Spring or a database. */
class LoginServiceTest {

    private final PasswordEncoder encoder = new PepperedPasswordEncoder("test-pepper");
    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final LoginService login = new LoginService(accounts, encoder, account -> "token-for-" + account.employeeId());

    private void account(long id, String email, boolean active, String passwordHash) {
        accounts.save(new Account(id, id, "EMP-" + id, email, "Ada", "Lovelace", "Employee", active, 1, passwordHash));
    }

    @Test
    void theRightPasswordReturnsATokenForThatAccount() {
        account(7, "ada@shift.dk", true, encoder.encode("correct horse"));

        LoginService.LoginResult result = login.login("ada@shift.dk", "correct horse");

        assertThat(result.token()).isEqualTo("token-for-7");
        assertThat(result.account().employeeId()).isEqualTo(7);
    }

    @Test
    void emailIsMatchedRegardlessOfCase() {
        account(7, "ada@shift.dk", true, encoder.encode("correct horse"));

        assertThat(login.login("Ada@Shift.DK", "correct horse").account().employeeId()).isEqualTo(7);
    }

    @Test
    void everyRefusalLooksTheSame() {
        // Same exception and message in all four cases, so a caller cannot probe which emails exist.
        account(1, "wrong@shift.dk", true, encoder.encode("correct horse"));
        account(2, "inactive@shift.dk", false, encoder.encode("correct horse"));
        account(3, "nopassword@shift.dk", true, null);

        assertRefused("wrong@shift.dk", "wrong password");
        assertRefused("inactive@shift.dk", "correct horse");
        assertRefused("nopassword@shift.dk", "correct horse");
        assertRefused("unknown@shift.dk", "correct horse");
    }

    @Test
    void aHashMadeWithAnotherPepperDoesNotVerify() {
        // The pepper is what protects a leaked database: without it, the right password still fails.
        account(7, "ada@shift.dk", true, new PepperedPasswordEncoder("other-pepper").encode("correct horse"));

        assertRefused("ada@shift.dk", "correct horse");
    }

    @Test
    void anOverlongPasswordIsRefusedNotAServerError() {
        // bcrypt's encode() throws on more than 72 bytes; login must keep answering 401 for such input, not 500.
        account(7, "ada@shift.dk", true, encoder.encode("correct horse"));

        assertRefused("ada@shift.dk", "x".repeat(100));
        assertRefused("unknown@shift.dk", "x".repeat(100));
    }

    private void assertRefused(String email, String password) {
        assertThatThrownBy(() -> login.login(email, password))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
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
