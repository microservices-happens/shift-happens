package dk.ek.shift_happens.scheduling.domain;

import java.util.Optional;

/** Port for swaps. The adapter lives in infrastructure/persistence. */
public interface ShiftSwapRepository {

    ShiftSwap save(ShiftSwap swap);

    Optional<ShiftSwap> findById(long shiftSwapId);

    Optional<ShiftSwap> findPendingForAssignment(long shiftAssignmentId);

    void delete(ShiftSwap swap);
}
