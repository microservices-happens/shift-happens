package dk.ek.shift_happens.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The staffing a shift requires is part of the shift aggregate, so its rules live here. */
class RequiredJobRoleTest {

    private static final Instant START = Instant.parse("2026-11-02T06:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T14:00:00Z");

    @Test
    void addingARoleBumpsTheVersionAndKeepsTheOthers() {
        Shift updated = shift(ShiftStatus.PLANNED).withRequiredJobRoleAdded(new RequiredJobRole(9, 1));

        assertThat(updated.requiredJobRoles()).containsExactly(new RequiredJobRole(70L, 7, 2),
                new RequiredJobRole(9, 1));
        assertThat(updated.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void theSameRoleCannotBeRequiredTwice() {
        assertThatThrownBy(() -> shift(ShiftStatus.PLANNED).withRequiredJobRoleAdded(new RequiredJobRole(7, 1)))
                .isInstanceOf(DuplicateRequiredJobRoleException.class)
                .hasMessageContaining("already requires job role 7");
    }

    @Test
    void aRowCanBeReplacedByItsId() {
        Shift updated = shift(ShiftStatus.PLANNED).withRequiredJobRoleReplaced(70L, new RequiredJobRole(11, 5));

        assertThat(updated.requiredJobRoles()).containsExactly(new RequiredJobRole(70L, 11, 5));
        assertThat(updated.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void replacingWithAnExistingRoleIsRejected() {
        Shift withTwoRoles = shift(ShiftStatus.PLANNED).withRequiredJobRoleAdded(new RequiredJobRole(90L, 11, 1));

        assertThatThrownBy(() -> withTwoRoles.withRequiredJobRoleReplaced(90L, new RequiredJobRole(7, 1)))
                .isInstanceOf(DuplicateRequiredJobRoleException.class);
    }

    @Test
    void aRowCanBeRemovedButAnUnknownIdCannot() {
        assertThat(shift(ShiftStatus.PLANNED).withoutRequiredJobRole(70L).requiredJobRoles()).isEmpty();

        assertThatThrownBy(() -> shift(ShiftStatus.PLANNED).withoutRequiredJobRole(999L))
                .isInstanceOf(RequiredJobRoleNotFoundException.class)
                .hasMessageContaining("no required job role row 999");
    }

    @Test
    void aCancelledShiftTakesNoNewStaff() {
        assertThatThrownBy(() -> shift(ShiftStatus.CANCELLED).withRequiredJobRoleAdded(new RequiredJobRole(11, 1)))
                .isInstanceOf(ShiftNotEditableException.class)
                .hasMessageContaining("the shift is cancelled");
    }

    @Test
    void aRoleNeedsAPositiveJobRoleAndAtLeastOneEmployee() {
        assertThatThrownBy(() -> new RequiredJobRole(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequiredJobRole(7, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Shift shift(ShiftStatus status) {
        return new Shift(42L, 2, 3, "Morning", START, END, status,
                List.of(new RequiredJobRole(70L, 7, 2)), 1, false);
    }
}
