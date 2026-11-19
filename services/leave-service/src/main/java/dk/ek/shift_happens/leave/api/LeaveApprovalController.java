package dk.ek.shift_happens.leave.api;

import dk.ek.shift_happens.leave.api.dto.LeaveApprovalRequest;
import dk.ek.shift_happens.leave.api.dto.LeaveApprovalResponse;
import dk.ek.shift_happens.leave.application.IdempotencyService;
import dk.ek.shift_happens.leave.application.LeaveApprovalService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A manager's decision about a leave request (contracts/openapi/leave.yaml). REJECTED is final;
 * APPROVED starts the leave-approval saga (P3.1) and answers 202 while Scheduling still has to answer, or 409 when
 * the request is not PENDING or the balance is too low. PUT is always a 409: a stored decision is final. DELETE of an approval
 * is not implemented: no use case needs it and the catalog defines no event for it.
 */
@RestController
@RequestMapping("/v1/leaveapprovals")
@Profile("command")
public class LeaveApprovalController {

    private final LeaveApprovalService approvals;
    private final IdempotencyService idempotency;

    LeaveApprovalController(LeaveApprovalService approvals, IdempotencyService idempotency) {
        this.approvals = approvals;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LeaveApprovalRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        return idempotency.execute(idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(LeaveApprovalResponse.from(approvals.decide(request.leaveRequestId(),
                        request.approverEmployeeId(), request.decision(), request.leaveComment(), correlationId))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LeaveApprovalResponse> replace(@PathVariable long id,
                                                         @Valid @RequestBody LeaveApprovalRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LeaveApprovalResponse.from(approvals.replace(id)));
    }
}
