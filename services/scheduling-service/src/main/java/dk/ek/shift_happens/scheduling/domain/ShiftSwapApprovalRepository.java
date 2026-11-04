package dk.ek.shift_happens.scheduling.domain;

import java.util.Optional;

/** Port for swap approvals. The adapter lives in infrastructure/persistence. */
public interface ShiftSwapApprovalRepository {

    ShiftSwapApproval save(ShiftSwapApproval approval);

    Optional<ShiftSwapApproval> findByShiftSwapId(long shiftSwapId);
}
