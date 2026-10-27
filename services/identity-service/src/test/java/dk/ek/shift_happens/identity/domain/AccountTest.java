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
}
