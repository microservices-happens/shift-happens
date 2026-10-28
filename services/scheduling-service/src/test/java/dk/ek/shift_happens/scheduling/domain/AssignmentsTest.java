package dk.ek.shift_happens.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Assignment lifecycle and the two rules the use case delegates to the domain. */
class AssignmentsTest {

    private static final Instant START = Instant.parse("2026-11-02T06:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T14:00:00Z");

    @Test
    void aNewAssignmentStartsAsAssignedAtVersionOne() {
        ShiftAssignment assignment = ShiftAssignment.create(42L, 7L, START);

        assertThat(assignment.status()).isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(assignment.aggregateVersion()).isEqualTo(1);
        assertThat(assignment.assignedDatetime()).isEqualTo(START);
        assertThat(assignment.deleted()).isFalse();
    }

    @Test
    void anAssignmentMovesForwardAndBumpsTheVersion() {
        ShiftAssignment approved = assignment().withStatus(AssignmentStatus.APPROVED, START, null);

        assertThat(approved.status()).isEqualTo(AssignmentStatus.APPROVED);
        assertThat(approved.aggregateVersion()).isEqualTo(2);
        assertThat(approved.checkInDatetime()).isEqualTo(START);
    }

    @Test
    void aFinishedAssignmentCannotBeReopened() {
        ShiftAssignment cancelled = assignment().withStatus(AssignmentStatus.CANCELLED, null, null);

        assertThatThrownBy(() -> cancelled.withStatus(AssignmentStatus.ASSIGNED, null, null))
                .isInstanceOf(AssignmentRejectedException.class)
                .hasMessageContaining("cannot change status from CANCELLED to ASSIGNED");
    }

    @Test
    void onlyActiveStatusesOccupyTheEmployee() {
        assertThat(AssignmentStatus.ASSIGNED.isActive()).isTrue();
        assertThat(AssignmentStatus.APPROVED.isActive()).isTrue();
        assertThat(AssignmentStatus.SWAPPED.isActive()).isTrue();
        assertThat(AssignmentStatus.CANCELLED.isActive()).isFalse();
        assertThat(AssignmentStatus.RELEASED_FOR_LEAVE.isActive()).isFalse();
    }

    @Test
    void deletingMarksTheAssignmentAndBumpsTheVersion() {
        ShiftAssignment deleted = assignment().markedDeleted();

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.aggregateVersion()).isEqualTo(2);
        assertThatThrownBy(() -> deleted.withStatus(AssignmentStatus.APPROVED, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSnapshotCopiesTheShiftWindow() {
        AssignmentSnapshot snapshot = new ShiftAssignment(5L, 42L, 7L, AssignmentStatus.ASSIGNED, START,
                null, null, 1, false).toSnapshot(shift(ShiftStatus.PLANNED));

        assertThat(snapshot.shiftStartDatetime()).isEqualTo(START);
        assertThat(snapshot.shiftEndDatetime()).isEqualTo(END);
        assertThat(snapshot.assignmentStatus()).isEqualTo("ASSIGNED");
    }

    @Test
    void aQualifiedEmployeeIsAcceptedAndAnUnqualifiedOneIsNot() {
        Shift shift = shift(ShiftStatus.PLANNED);

        assertThat(shift.accepts(employee(List.of(7L)))).isTrue();
        assertThat(shift.accepts(employee(List.of(9L, 11L)))).isFalse();
    }

    @Test
    void anEmployeeWithNoJobRolesCannotTakeAShiftThatRequiresThem() {
        assertThat(shift(ShiftStatus.PLANNED).accepts(employee(List.of()))).isFalse();
    }

    @Test
    void overlapIsHalfOpenAtBothEnds() {
        Shift morning = shift(ShiftStatus.PLANNED);

        assertThat(morning.overlaps(START, END)).isTrue();
        assertThat(morning.overlaps(START.minusSeconds(3600), START.plusSeconds(3600))).isTrue();
        // Touching at either boundary is not an overlap.
        assertThat(morning.overlaps(END, END.plusSeconds(3600))).isFalse();
        assertThat(morning.overlaps(START.minusSeconds(3600), START)).isFalse();
    }

    private static ShiftAssignment assignment() {
        return new ShiftAssignment(5L, 42L, 7L, AssignmentStatus.ASSIGNED, START, null, null, 1, false);
    }

    private static Shift shift(ShiftStatus status) {
        return new Shift(42L, 2, 3, "Morning", START, END, status,
                List.of(new RequiredJobRole(70L, 7, 2)), 1, false);
    }

    private static EmployeeReplica employee(List<Long> jobRoleIds) {
        return new EmployeeReplica(7, "Ada", "Lovelace", "ACTIVE", jobRoleIds, null, 1, null);
    }
}
