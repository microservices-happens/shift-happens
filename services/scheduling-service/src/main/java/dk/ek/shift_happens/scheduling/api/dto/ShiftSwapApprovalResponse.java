package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.ShiftSwapApproval;

import java.time.Instant;

/** Response body of the swap approval endpoints (contracts/openapi/scheduling.yaml). */
public record ShiftSwapApprovalResponse(
        Long shiftSwapApprovalId,
        long shiftSwapId,
        long approverEmployeeId,
        String decision,
        String shiftSwapComment,
        Instant decisionDatetime) {

    public static ShiftSwapApprovalResponse from(ShiftSwapApproval approval) {
        return new ShiftSwapApprovalResponse(approval.shiftSwapApprovalId(), approval.shiftSwapId(),
                approval.approverEmployeeId(), approval.decision().name(), approval.comment(),
                approval.decisionDatetime());
    }
}
