package dk.ek.shift_happens.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Business rules of a shift, testable without a Spring context. */
class ShiftTest {

    private static final Instant START = Instant.parse("2026-11-02T06:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T14:00:00Z");

    @Test
    void aNewShiftStartsAtVersionOneWithoutAnId() {
        Shift shift = Shift.create(newShift(START, END, ShiftStatus.PLANNED));

        assertThat(shift.shiftId()).isNull();
        assertThat(shift.aggregateVersion()).isEqualTo(1);
        assertThat(shift.status()).isEqualTo(ShiftStatus.PLANNED);
        assertThat(shift.deleted()).isFalse();
    }

    @Test
    void theSnapshotCarriesTheStatusAsTextForTheEventPayload() {
        // A snapshot is only published for a saved shift, so it always carries the assigned id.
        Shift shift = savedShift(ShiftStatus.PUBLISHED, 1, false);

        ShiftSnapshot snapshot = shift.snapshot();

        assertThat(snapshot.shiftId()).isEqualTo(42L);
        assertThat(snapshot.shiftStatus()).isEqualTo("PUBLISHED");
        assertThat(snapshot.requiredJobRoles()).containsExactly(new RequiredJobRoleSnapshot(7, 2));
    }

    @Test
    void aShiftMustEndAfterItStarts() {
        assertThatThrownBy(() -> Shift.create(newShift(END, START, ShiftStatus.PLANNED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endDatetime must be after startDatetime");
    }

    @Test
    void aZeroLengthShiftIsRejected() {
        assertThatThrownBy(() -> Shift.create(newShift(START, START, ShiftStatus.PLANNED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endDatetime must be after startDatetime");
    }

    @Test
    void aShiftNeedsAName() {
        assertThatThrownBy(() -> Shift.create(new NewShift(1, 1, "  ", START, END, ShiftStatus.PLANNED, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shiftName is required");
    }

    @Test
    void theSameJobRoleCannotBeRequiredTwice() {
        assertThatThrownBy(() -> Shift.create(new NewShift(1, 1, "Morning", START, END, ShiftStatus.PLANNED,
                List.of(new RequiredJobRole(7, 1), new RequiredJobRole(7, 3)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("each jobRoleId may appear only once");
    }

    @Test
    void aRequiredJobRoleNeedsAtLeastOneEmployee() {
        assertThatThrownBy(() -> new RequiredJobRole(7, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredEmployeeCount must be at least 1");
    }

    @Test
    void aShiftCannotBeCreatedAlreadyCancelledOrLocked() {
        assertThatThrownBy(() -> Shift.create(newShift(START, END, ShiftStatus.CANCELLED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a new shift must be PLANNED or PUBLISHED");
        assertThatThrownBy(() -> Shift.create(newShift(START, END, ShiftStatus.LOCKED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingStatusDefaultsToPlanned() {
        assertThat(Shift.create(newShift(START, END, null)).status()).isEqualTo(ShiftStatus.PLANNED);
    }

    @Test
    void anUnsavedShiftHasNoPublishableSnapshot() {
        assertThatThrownBy(() -> Shift.create(newShift(START, END, ShiftStatus.PLANNED)).snapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be saved before");
    }

    @Test
    void anUpdateBumpsTheAggregateVersionAndKeepsTheId() {
        Shift updated = savedShift(ShiftStatus.PLANNED, 1, false)
                .updated(new NewShift(5, 6, "Late shift", START, END, ShiftStatus.PLANNED, List.of()));

        assertThat(updated.shiftId()).isEqualTo(42L);
        assertThat(updated.aggregateVersion()).isEqualTo(2);
        assertThat(updated.shiftName()).isEqualTo("Late shift");
        assertThat(updated.departmentId()).isEqualTo(5);
        assertThat(updated.deleted()).isFalse();
    }

    @Test
    void aPlanCanBePublished() {
        assertThat(savedShift(ShiftStatus.PLANNED, 1, false)
                .updated(newShift(START, END, ShiftStatus.PUBLISHED)).status())
                .isEqualTo(ShiftStatus.PUBLISHED);
    }

    @Test
    void anUpdateCannotMoveTheStatusBackwards() {
        assertThatThrownBy(() -> savedShift(ShiftStatus.PUBLISHED, 1, false)
                .updated(newShift(START, END, ShiftStatus.PLANNED)))
                .isInstanceOf(ShiftNotEditableException.class)
                .hasMessageContaining("cannot change status from PUBLISHED to PLANNED");
    }

    @Test
    void aCancelledShiftCannotBeChanged() {
        assertThatThrownBy(() -> savedShift(ShiftStatus.CANCELLED, 2, false)
                .updated(newShift(START, END, ShiftStatus.PLANNED)))
                .isInstanceOf(ShiftNotEditableException.class);
    }

    @Test
    void anUpdateWithoutJobRolesKeepsTheExistingOnes() {
        Shift updated = savedShift(ShiftStatus.PLANNED, 1, false)
                .updated(new NewShift(2, 3, "Morning", START, END, ShiftStatus.PLANNED, null));

        assertThat(updated.requiredJobRoles()).containsExactly(new RequiredJobRole(7, 2));
    }

    @Test
    void deletingMarksTheShiftAndBumpsTheVersion() {
        Shift deleted = savedShift(ShiftStatus.PUBLISHED, 3, false).markedDeleted();

        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.aggregateVersion()).isEqualTo(4);
    }

    @Test
    void aDeletedShiftCannotBeUpdated() {
        assertThatThrownBy(() -> savedShift(ShiftStatus.PUBLISHED, 3, true)
                .updated(newShift(START, END, ShiftStatus.PUBLISHED)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a deleted shift cannot be changed");
    }

    private static Shift savedShift(ShiftStatus status, long version, boolean deleted) {
        return new Shift(42L, 2, 3, "Morning", START, END, status,
                List.of(new RequiredJobRole(7, 2)), version, deleted);
    }

    private static NewShift newShift(Instant start, Instant end, ShiftStatus status) {
        return new NewShift(2, 3, "Morning", start, end, status, List.of(new RequiredJobRole(7, 2)));
    }
}
