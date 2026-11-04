package dk.ek.shift_happens.scheduling.domain;

/** How Scheduling answered the saga: the assignments were released, or nothing changed. */
public enum LeaveReleaseStatus {
    COMPLETED,
    REJECTED
}
