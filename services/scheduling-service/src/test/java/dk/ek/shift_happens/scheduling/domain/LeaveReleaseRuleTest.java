package dk.ek.shift_happens.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** contracts/events/catalog.md: a shift is locked when it starts within 24 hours or was checked into. */
class LeaveReleaseRuleTest {

    private static final Instant NOW = Instant.parse("2027-03-01T12:00:00Z");

    @Test
    void aShiftStartingWithin24HoursIsLocked() {
        assertThat(LeaveReleaseRule.isLocked(shift(NOW.plusSeconds(3600), ShiftStatus.PUBLISHED),
                assignment(null), NOW)).isTrue();
    }

    @Test
    void aShiftStartingLaterIsNotLocked() {
        assertThat(LeaveReleaseRule.isLocked(shift(NOW.plusSeconds(24 * 3600 + 60), ShiftStatus.PUBLISHED),
                assignment(null), NOW)).isFalse();
    }

    @Test
    void aShiftAlreadyMarkedLockedBlocks() {
        assertThat(LeaveReleaseRule.isLocked(shift(NOW.plusSeconds(30 * 24 * 3600), ShiftStatus.LOCKED),
                assignment(null), NOW)).isTrue();
    }

    @Test
    void aCheckedInAssignmentBlocks() {
        assertThat(LeaveReleaseRule.isLocked(shift(NOW.plusSeconds(30 * 24 * 3600), ShiftStatus.PUBLISHED),
                assignment(NOW.minusSeconds(60)), NOW)).isTrue();
    }

    private static Shift shift(Instant start, ShiftStatus status) {
        return new Shift(42L, 2, 3, "Morning", start, start.plusSeconds(8 * 3600), status,
                List.of(new RequiredJobRole(70L, 7, 1)), 1, false);
    }

    private static ShiftAssignment assignment(Instant checkIn) {
        return new ShiftAssignment(5L, 42L, 7L, AssignmentStatus.ASSIGNED, NOW, checkIn, null, 1, false);
    }
}
