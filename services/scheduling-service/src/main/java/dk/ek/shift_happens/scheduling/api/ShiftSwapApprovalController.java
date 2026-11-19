package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.api.dto.CreateShiftSwapApprovalRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftSwapApprovalResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftSwapApprovalRequest;
import dk.ek.shift_happens.scheduling.application.IdempotencyService;
import dk.ek.shift_happens.scheduling.application.ShiftSwapService;
import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The manager's decision about a swap (contracts/openapi/scheduling.yaml).
 *
 * <p>POST decides. PUT corrects the record of that decision (approver and comment); the decision itself
 * cannot flip, because the handover it may have caused is already published and the event catalog has
 * no reversal event. DELETE removes the record, and the swap keeps the state it published, so nothing
 * is published again.
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

    @PutMapping("/{id}")
    public ResponseEntity<ShiftSwapApprovalResponse> replace(
            @PathVariable long id,
            @Valid @RequestBody UpdateShiftSwapApprovalRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        ShiftSwapApprovalResponse updated = ShiftSwapApprovalResponse.from(swaps.correctDecision(id,
                request.shiftSwapId(), request.approverEmployeeId(),
                ApprovalDecision.valueOf(request.decision()), request.shiftSwapComment(), correlationId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        swaps.retractDecision(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
