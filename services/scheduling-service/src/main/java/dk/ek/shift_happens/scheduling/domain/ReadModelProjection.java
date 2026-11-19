package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/**
 * Write port of the Mongo read model (P2.10). The adapter lives in infrastructure/readmodel.
 *
 * <p>The contract of every method is the same: apply the record only if its {@code aggregateVersion} is
 * higher than the stored one, never clear a tombstone, and ignore anything that arrives out of order.
 * That is what makes the projection a commutative message handler (contracts/README.md).
 */
public interface ReadModelProjection {

    void upsertShift(ShiftView shift);

    void deleteShift(long shiftId, long aggregateVersion, Instant deletedAt);

    void upsertAssignment(AssignmentView assignment);

    void deleteAssignment(long shiftAssignmentId, long aggregateVersion, Instant deletedAt);

    void upsertEmployee(EmployeeView employee);

    void deleteEmployee(long employeeId, long aggregateVersion, Instant deletedAt);

    void upsertSwap(SwapView swap);

    /** Keyed by shift and job role, newest {@code suggestedAt} wins. */
    void upsertSuggestion(SuggestionView suggestion);

    /** Empties the read-model collections. Only the rebuild routine calls this. */
    void clear();
}
