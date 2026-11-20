package dk.ek.shift_happens.identity.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may set a password (contracts/openapi/identity.yaml), without Spring. */
class CallerTest {

    @Test
    void anEmployeeMaySetTheirOwnPassword() {
        assertThat(new Caller(7, false).maySetPasswordOf(7)).isTrue();
    }

    @Test
    void aNonAdminMayNotSetSomeoneElsesPassword() {
        // Otherwise any employee (or a Manager, who is not an admin here) could take over another account.
        assertThat(new Caller(7, false).maySetPasswordOf(8)).isFalse();
    }

    @Test
    void anAdministratorMaySetAnyonesPassword() {
        // Admins reset passwords for people who forgot them, so they are not limited to their own.
        assertThat(new Caller(1, true).maySetPasswordOf(8)).isTrue();
        assertThat(new Caller(1, true).maySetPasswordOf(1)).isTrue();
    }
}
