package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.api.dto.CreateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftAssignmentResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.application.ShiftAssignmentService;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
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

import java.time.Instant;

/**
 * Who works which shift (contracts/openapi/scheduling.yaml).
 *
 * <p>The write itself is validated against local data only: the employee replica (active, qualified)
 * and this service'"'"'s own assignments (no overlap).
 */
@RestController
@RequestMapping("/v1/shiftassignments")
@Profile("command")
public class ShiftAssignmentController {

    private final ShiftAssignmentService assignments;

    ShiftAssignmentController(ShiftAssignmentService assignments) {
        this.assignments = assignments;
    }

    @PostMapping
    public ResponseEntity<ShiftAssignmentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateShiftAssignmentRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        IdempotencyKeys.requireUuid(idempotencyKey);
        ShiftAssignmentResponse created = ShiftAssignmentResponse.from(assignments.create(
                request.shiftId(), request.employeeId(), Instant.now(), correlationId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShiftAssignmentResponse> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateShiftAssignmentRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        ShiftAssignmentResponse updated = ShiftAssignmentResponse.from(assignments.update(id,
                request.shiftId(), request.employeeId(), AssignmentStatus.valueOf(request.assignmentStatus()),
                request.checkInDatetime(), request.checkOutDatetime(), correlationId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable long id,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        assignments.delete(id, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
