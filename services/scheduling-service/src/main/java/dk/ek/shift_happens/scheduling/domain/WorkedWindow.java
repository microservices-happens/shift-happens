package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/** A window an employee already works, used to add up hoursThisWeek. */
public record WorkedWindow(Instant start, Instant end) {

    public double hours() {
        return (end.toEpochMilli() - start.toEpochMilli()) / 3_600_000.0;
    }
}
