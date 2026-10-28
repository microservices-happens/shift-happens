package dk.ek.shift_happens.scheduling.domain;

/**
 * Lifecycle of a shift (contracts/openapi/scheduling.yaml, contracts/graphql/scheduling.graphql).
 *
 * <p>LOCKED means the shift starts within 24 hours, so assignments can no longer be released for
 * leave. Marking shifts LOCKED on a timer is a later slice; the value is modelled here because the
 * contract has it and an update may set it.
 */
public enum ShiftStatus {
    PLANNED,
    PUBLISHED,
    CANCELLED,
    LOCKED;

    public boolean isCancelled() {
        return this == CANCELLED;
    }

    /**
     * A shift moves forward through its life and can always be cancelled; it never goes back.
     * Keeping the rule here means an illegal transition is rejected the same way everywhere, and it
     * is unit-testable without a database.
     */
    public boolean canTransitionTo(ShiftStatus target) {
        if (target == this) {
            return true;
        }
        return switch (this) {
            case PLANNED -> target == PUBLISHED || target == CANCELLED;
            case PUBLISHED -> target == LOCKED || target == CANCELLED;
            case LOCKED -> target == CANCELLED;
            case CANCELLED -> false;
        };
    }
}
