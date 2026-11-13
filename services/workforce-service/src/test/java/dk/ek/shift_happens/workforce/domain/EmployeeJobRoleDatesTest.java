package dk.ek.shift_happens.workforce.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeeJobRoleDatesTest {

    @Test
    void rejectsAnExpiredAssignmentAndAcceptsOneValidThroughToday() {
        LocalDate today = LocalDate.of(2026, 11, 11);
        LocalDate assigned = today.minusDays(10);

        assertThatThrownBy(() -> EmployeeJobRoleDates.validate(assigned, today.minusDays(1), today))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> EmployeeJobRoleDates.validate(assigned, today, today))
                .doesNotThrowAnyException();
    }
}
