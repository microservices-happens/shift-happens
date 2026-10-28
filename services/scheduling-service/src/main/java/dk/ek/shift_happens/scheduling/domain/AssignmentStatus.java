package dk.ek.shift_happens.scheduling.domain;

/**
 * Lifecycle of an assignment (contracts/graphql/scheduling.graphql, AssignmentStatus).
 *
 * <p>Only ASSIGNED, APPROVED and SWAPPED count as active for the overlap rule; a cancelled or
 * released assignment no longer occupies the employee.
 */
public enum AssignmentStatus {
    ASSIGNED,
    APPROVED,
    CANCELLED,
    SWAPPED,
    RELEASED_FOR_LEAVE;

    public boolean isActive() {
        return this == ASSIGNED || this == APPROVED || this == SWAPPED;
    }

    public boolean isCancelled() {
        return this == CANCELLED;
    }

    /** Cancelling and releasing are always allowed; nothing leaves CANCELLED or RELEASED_FOR_LEAVE. */
    public boolean canTransitionTo(AssignmentStatus target) {
        if (target == this) {
            return true;
        }
        return switch (this) {
            case ASSIGNED -> target == APPROVED || target == SWAPPED || target == CANCELLED
                    || target == RELEASED_FOR_LEAVE;
            case APPROVED -> target == SWAPPED || target == CANCELLED || target == RELEASED_FOR_LEAVE;
            case SWAPPED -> target == CANCELLED || target == RELEASED_FOR_LEAVE;
            case CANCELLED, RELEASED_FOR_LEAVE -> false;
        };
    }
}
