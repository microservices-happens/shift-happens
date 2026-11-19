package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Request body of PUT /v1/shiftswapapprovals/{id} (contracts/openapi/scheduling.yaml).
 *
 * <p>Same shape as the create request, so a client can send the contract's ShiftSwapApproval to both.
 */
public record UpdateShiftSwapApprovalRequest(
        @Positive long shiftSwapId,
        @Positive long approverEmployeeId,
        @NotNull @Pattern(regexp = "APPROVED|REJECTED", message = "must be APPROVED or REJECTED")
        String decision,
        String shiftSwapComment) {
}
