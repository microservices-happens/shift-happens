package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.api.dto.CreateShiftApprovalRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftApprovalResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftApprovalRequest;
import dk.ek.shift_happens.scheduling.application.IdempotencyService;
import dk.ek.shift_happens.scheduling.application.ShiftApprovalService;
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

/** Manager decisions about assignments (contracts/openapi/scheduling.yaml). */
@RestController
@RequestMapping("/v1/shiftapprovals")
@Profile("command")
public class ShiftApprovalController {

    private final ShiftApprovalService approvals;
    private final IdempotencyService idempotency;

    ShiftApprovalController(ShiftApprovalService approvals, IdempotencyService idempotency) {
        this.approvals = approvals;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateShiftApprovalRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        return idempotency.execute(idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ShiftApprovalResponse.from(approvals.decide(request.shiftAssignmentId(),
                        request.approverEmployeeId(), ApprovalDecision.valueOf(request.decision()),
                        request.approvalComment(), correlationId))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShiftApprovalResponse> replace(
            @PathVariable long id,
            @Valid @RequestBody UpdateShiftApprovalRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        ShiftApprovalResponse updated = ShiftApprovalResponse.from(approvals.replace(id,
                ApprovalDecision.valueOf(request.decision()), request.approvalComment(), correlationId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        approvals.delete(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
