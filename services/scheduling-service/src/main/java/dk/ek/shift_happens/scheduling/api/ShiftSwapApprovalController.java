package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.api.dto.CreateShiftSwapApprovalRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftSwapApprovalResponse;
import dk.ek.shift_happens.scheduling.application.IdempotencyService;
import dk.ek.shift_happens.scheduling.application.ShiftSwapService;
import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The manager's decision about a swap (contracts/openapi/scheduling.yaml).
 *
 * <p>POST is the only decision path: the catalog defines no reversal flow (no swap.updated or
 * swap.reverted event), so PUT/DELETE of a decision are deliberately not implemented yet.
 */
@RestController
@RequestMapping("/v1/shiftswapapprovals")
@Profile("command")
public class ShiftSwapApprovalController {

    private final ShiftSwapService swaps;
    private final IdempotencyService idempotency;

    ShiftSwapApprovalController(ShiftSwapService swaps, IdempotencyService idempotency) {
        this.swaps = swaps;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateShiftSwapApprovalRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        return idempotency.execute(idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ShiftSwapApprovalResponse.from(swaps.decide(request.shiftSwapId(),
                        request.approverEmployeeId(), ApprovalDecision.valueOf(request.decision()),
                        request.shiftSwapComment(), correlationId))));
    }
}
