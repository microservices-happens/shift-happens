package dk.ek.shift_happens.scheduling.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts/openapi/scheduling.yaml, POST /v1/shiftswaps: who may request a swap for whom. */
class ViewerTest {

    @Test
    void anAdministratorOrManagerMayRequestASwapForAnyone() {
        assertThat(new Viewer(1L, "Administrator").mayRequestSwapFrom(42)).isTrue();
        assertThat(new Viewer(1L, "Manager").mayRequestSwapFrom(42)).isTrue();
    }

    @Test
    void anEmployeeMayOnlyRequestASwapForTheirOwnAssignment() {
        assertThat(new Viewer(42L, "Employee").mayRequestSwapFrom(42)).isTrue();
        assertThat(new Viewer(41L, "Employee").mayRequestSwapFrom(42)).isFalse();
    }

    @Test
    void aTokenWithoutAnEmployeeIdMayNotRequestASwapUnlessItSeesEverything() {
        assertThat(new Viewer(null, "Employee").mayRequestSwapFrom(42)).isFalse();
        assertThat(new Viewer(null, "Manager").mayRequestSwapFrom(42)).isTrue();
    }
}
