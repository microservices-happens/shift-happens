package dk.ek.shift_happens.scheduling.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * "Locked" as contracts/events/catalog.md defines it for the leave saga: a shift that starts within
 * 24 hours, one that is already LOCKED, or one the employee has checked into. A locked shift blocks
 * the whole release (nothing is changed), which is what makes the saga decide to compensate.
 */
public final class LeaveReleaseRule {

    private static final Duration WINDOW = Duration.ofHours(24);

    private LeaveReleaseRule() {
    }

    public static boolean isLocked(Shift shift, ShiftAssignment assignment, Instant now) {
        return shift.status() == ShiftStatus.LOCKED
                || assignment.checkInDatetime() != null
                || !shift.startDatetime().isAfter(now.plus(WINDOW));
    }
}
