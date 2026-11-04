package dk.ek.shift_happens.identity.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The projection rules that make the consumer safe, without Spring or a database. */
class AccountTest {

    private static EmployeeSnapshot snapshot(String email, String status) {
        return new EmployeeSnapshot(7L, "EMP-0007", "Employee", "Ada", "Lovelace", email,
                null, null, status, 1L, List.of());
    }

    @Test
    void anActiveEmployeeGetsAnActiveAccount() {
        Account account = Account.fromSnapshot(snapshot("ada@shift.dk", "ACTIVE"), 1);

        assertThat(account.active()).isTrue();
        assertThat(account.role()).isEqualTo("Employee");
        assertThat(account.aggregateVersion()).isEqualTo(1);
    }

    @Test
    void aNonActiveStatusDisablesTheAccount() {
        Account account = Account.fromSnapshot(snapshot("ada@shift.dk", "INACTIVE"), 1);

        assertThat(account.active()).isFalse();
    }

    @Test
    void onlyANewerVersionIsApplied() {
        Account current = Account.fromSnapshot(snapshot("ada@shift.dk", "ACTIVE"), 3);

        assertThat(current.accepts(4)).isTrue();
        assertThat(current.accepts(3)).isFalse();
        assertThat(current.accepts(2)).isFalse();
    }

    @Test
    void aTombstoneDisablesTheAccountAndKeepsIt() {
        Account current = Account.fromSnapshot(snapshot("ada@shift.dk", "ACTIVE"), 1);

        Account deleted = current.deleted(2);

        assertThat(deleted.active()).isFalse();
        assertThat(deleted.aggregateVersion()).isEqualTo(2);
        assertThat(deleted.email()).isEqualTo("ada@shift.dk");
    }

    @Test
    void aWorkforceUpdateKeepsThePassword() {
        // Identity owns the password; an employee update from workforce must not wipe it, or every
        // profile edit would lock the employee out.
        Account withPassword = withPassword(Account.fromSnapshot(snapshot("ada@shift.dk", "ACTIVE"), 1), "hash");

        Account updated = withPassword.withSnapshot(snapshot("ada.l@shift.dk", "ACTIVE"), 2);

        assertThat(updated.passwordHash()).isEqualTo("hash");
        assertThat(updated.email()).isEqualTo("ada.l@shift.dk");
    }

    @Test
    void onlyAnActiveAccountWithAPasswordCanLogIn() {
        Account noPassword = Account.fromSnapshot(snapshot("ada@shift.dk", "ACTIVE"), 1);
        Account active = withPassword(noPassword, "hash");
        Account deactivated = withPassword(Account.fromSnapshot(snapshot("ada@shift.dk", "INACTIVE"), 1), "hash");
        Account deleted = active.deleted(2);

        assertThat(active.canLogInWithPassword()).isTrue();
        assertThat(noPassword.canLogInWithPassword()).isFalse();
        assertThat(deactivated.canLogInWithPassword()).isFalse();
        // A deleted employee keeps the hash but must be refused (README "Done when").
        assertThat(deleted.passwordHash()).isEqualTo("hash");
        assertThat(deleted.canLogInWithPassword()).isFalse();
    }

    private static Account withPassword(Account a, String hash) {
        return new Account(a.accountId(), a.employeeId(), a.employeeNumber(), a.email(), a.firstName(),
                a.lastName(), a.role(), a.active(), a.aggregateVersion(), hash);
    }
}
