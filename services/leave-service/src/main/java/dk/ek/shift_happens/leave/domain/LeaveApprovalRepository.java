package dk.ek.shift_happens.leave.domain;

import java.util.Optional;

/** Port for leave approvals. */
public interface LeaveApprovalRepository {

    LeaveApproval save(LeaveApproval approval);

    Optional<LeaveApproval> findById(long leaveApprovalId);
}
