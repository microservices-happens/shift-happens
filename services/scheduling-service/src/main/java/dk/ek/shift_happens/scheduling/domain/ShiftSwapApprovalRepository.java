package dk.ek.shift_happens.scheduling.domain;

import java.util.Optional;

/** Port for swap approvals. The adapter lives in infrastructure/persistence. */
public interface ShiftSwapApprovalRepository {

    ShiftSwapApproval save(ShiftSwapApproval approval);

    Optional<ShiftSwapApproval> findById(long shiftSwapApprovalId);

    Optional<ShiftSwapApproval> findByShiftSwapId(long shiftSwapId);

    /** DELETE /v1/shiftswapapprovals/{id}: removes the record of a decision, not the decision itself. */
    void delete(ShiftSwapApproval approval);
}