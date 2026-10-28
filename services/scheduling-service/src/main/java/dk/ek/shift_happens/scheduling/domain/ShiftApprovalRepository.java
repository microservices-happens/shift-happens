package dk.ek.shift_happens.scheduling.domain;

import java.util.Optional;

/** Port for approvals. The adapter lives in infrastructure/persistence. */
public interface ShiftApprovalRepository {

    ShiftApproval save(ShiftApproval approval);

    Optional<ShiftApproval> findById(long shiftApprovalId);

    Optional<ShiftApproval> findByShiftAssignmentId(long shiftAssignmentId);

    void delete(ShiftApproval approval);
}
