package dk.ek.shift_happens.scheduling;

import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.domain.NewShift;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract makes the staffing plan optional on POST /v1/shifts: a manager may create the shift first
 * and add roles through /v1/shiftrequiredjobroles afterwards. A missing list must not become a 400 and must
 * not carry nulls into the domain.
 */
class CreateShiftRequestTest {

    private static CreateShiftRequest request(List<RequiredJobRoleRequest> roles) {
        return new CreateShiftRequest(
                1L,
                2L,
                "Evening",
                Instant.parse("2035-01-01T17:00:00Z"),
                Instant.parse("2035-01-01T21:00:00Z"),
                "PLANNED",
                roles);
    }

    @Test
    void missingStaffingPlanBecomesAnEmptyList() {
        NewShift shift = request(null).toNewShift();

        assertThat(shift.requiredJobRoles()).isEmpty();
        assertThat(shift.status()).isEqualTo(ShiftStatus.PLANNED);
    }

    @Test
    void staffingPlanIsMappedToDomainRows() {
        NewShift shift = request(List.of(new RequiredJobRoleRequest(3L, 2))).toNewShift();

        assertThat(shift.requiredJobRoles())
                .singleElement()
                .satisfies(role -> {
                    assertThat(role.jobRoleId()).isEqualTo(3L);
                    assertThat(role.requiredEmployeeCount()).isEqualTo(2);
                });
    }
}
