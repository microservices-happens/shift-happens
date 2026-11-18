package dk.ek.shift_happens.leave.api;

import dk.ek.shift_happens.leave.api.dto.LeaveTypeRequest;
import dk.ek.shift_happens.leave.api.dto.LeaveTypeResponse;
import dk.ek.shift_happens.leave.application.IdempotencyService;
import dk.ek.shift_happens.leave.application.LeaveTypeService;
import dk.ek.shift_happens.leave.domain.LeaveType;
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
 * Leave types, from contracts/openapi/leave.yaml. Writes only: reads belong to the query role (#46).
 * The catalog defines no leave-type events, so none of these publish anything.
 */
@RestController
@RequestMapping("/v1/leavetypes")
@Profile("command")
public class LeaveTypeController {

    private final LeaveTypeService leaveTypes;
    private final IdempotencyService idempotency;

    LeaveTypeController(LeaveTypeService leaveTypes, IdempotencyService idempotency) {
        this.leaveTypes = leaveTypes;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody LeaveTypeRequest request) {

        return idempotency.execute(idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(LeaveTypeResponse.from(leaveTypes.create(toDomain(request)))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<LeaveTypeResponse> replace(@PathVariable long id,
                                                     @Valid @RequestBody LeaveTypeRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(LeaveTypeResponse.from(leaveTypes.replace(id, toDomain(request))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        leaveTypes.delete(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static LeaveType toDomain(LeaveTypeRequest request) {
        return new LeaveType(null, request.leaveTypeName().strip(), request.leaveTypeDescription(),
                request.requiresApprovalOrDefault(), request.paidLeaveOrDefault());
    }
}
