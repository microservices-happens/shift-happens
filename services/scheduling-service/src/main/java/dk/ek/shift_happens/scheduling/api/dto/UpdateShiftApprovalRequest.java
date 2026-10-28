package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Request body of PUT /v1/shiftapprovals/{id} (contracts/openapi/scheduling.yaml). */
public record UpdateShiftApprovalRequest(
        @Positive long shiftAssignmentId,
        @Positive long approverEmployeeId,
        @NotNull @Pattern(regexp = "APPROVED|REJECTED", message = "must be APPROVED or REJECTED")
        String decision,
        String approvalComment) {
}
