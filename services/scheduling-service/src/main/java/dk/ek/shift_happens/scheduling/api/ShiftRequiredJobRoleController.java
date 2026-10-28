package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.api.dto.CreateRequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftRequiredJobRoleResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateRequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.application.ShiftService;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;
import dk.ek.shift_happens.scheduling.domain.Shift;
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
 * The staffing a shift requires, from contracts/openapi/scheduling.yaml.
 *
 * <p>There is no event of its own: every change republishes the whole shift as
 * scheduling.shift.updated.v1, which is what the contract describes and what keeps a consumer from
 * having to stitch a second stream together.
 */
@RestController
@RequestMapping("/v1/shiftrequiredjobroles")
@Profile("command")
public class ShiftRequiredJobRoleController {

    private final ShiftService shifts;

    ShiftRequiredJobRoleController(ShiftService shifts) {
        this.shifts = shifts;
    }

    @PostMapping
    public ResponseEntity<ShiftRequiredJobRoleResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRequiredJobRoleRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        IdempotencyKeys.requireUuid(idempotencyKey);
        Shift shift = shifts.addRequiredJobRole(request.shiftId(), request.toRequiredJobRole(), correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ShiftRequiredJobRoleResponse.from(
                shift.shiftId(), rowWithJobRole(shift, request.jobRoleId())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShiftRequiredJobRoleResponse> replace(
            @PathVariable long id,
            @Valid @RequestBody UpdateRequiredJobRoleRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        Shift shift = shifts.replaceRequiredJobRole(id, request.shiftId(), request.toRequiredJobRole(),
                correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ShiftRequiredJobRoleResponse.from(
                shift.shiftId(), rowWithId(shift, id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable long id,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        shifts.removeRequiredJobRole(id, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static RequiredJobRole rowWithJobRole(Shift shift, long jobRoleId) {
        return shift.requiredJobRoles().stream()
                .filter(role -> role.jobRoleId() == jobRoleId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the shift does not have job role " + jobRoleId));
    }

    private static RequiredJobRole rowWithId(Shift shift, long shiftRequiredJobRoleId) {
        return shift.requiredJobRoles().stream()
                .filter(role -> role.shiftRequiredJobRoleId() != null
                        && role.shiftRequiredJobRoleId() == shiftRequiredJobRoleId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the shift does not have row " + shiftRequiredJobRoleId));
    }
}
