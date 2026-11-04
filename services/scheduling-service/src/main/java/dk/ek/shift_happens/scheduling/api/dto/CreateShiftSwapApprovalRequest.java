package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Request body of POST /v1/shiftswapapprovals (contracts/openapi/scheduling.yaml). */
public record CreateShiftSwapApprovalRequest(
        @Positive long shiftSwapId,
        @Positive long approverEmployeeId,
        @NotNull @Pattern(regexp = "APPROVED|REJECTED", message = "must be APPROVED or REJECTED")
        String decision,
        String shiftSwapComment) {
}
