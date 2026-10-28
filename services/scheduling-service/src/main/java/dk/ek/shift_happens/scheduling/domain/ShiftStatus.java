package dk.ek.shift_happens.scheduling.domain;

/**
 * Lifecycle of a shift (contracts/openapi/scheduling.yaml, contracts/graphql/scheduling.graphql).
 *
 * <p>LOCKED means the shift starts within 24 hours, so assignments can no longer be released for
 * leave. Marking shifts LOCKED is a later slice; the value is here because the contract has it.
 */
public enum ShiftStatus {
    PLANNED,
    PUBLISHED,
    CANCELLED,
    LOCKED;

    public boolean isCancelled() {
        return this == CANCELLED;
    }
}
