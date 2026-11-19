package dk.ek.shift_happens.leave.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The saga timeout setting is validated when the service is built, without Spring. */
class LeaveApprovalSagaServiceTest {

    private static LeaveApprovalSagaService serviceWith(String timeout) {
        return new LeaveApprovalSagaService(null, null, null, null, null, null, null, null, Clock.systemUTC(),
                timeout);
    }

    @Test
    void aZeroTimeoutIsRefusedAtBoot() {
        assertThatThrownBy(() -> serviceWith("PT0S")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SAGA_TIMEOUT");
    }

    @Test
    void aNegativeTimeoutIsRefusedAtBoot() {
        assertThatThrownBy(() -> serviceWith("-PT1M")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPositiveTimeoutIsAccepted() {
        assertThatCode(() -> serviceWith("PT5M")).doesNotThrowAnyException();
    }
}
