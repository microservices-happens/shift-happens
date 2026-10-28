package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftRequest;
import dk.ek.shift_happens.scheduling.application.ShiftService;
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
 * Write API of contracts/openapi/scheduling.yaml, served by the command role.
 *
 * <p>Reads are GraphQL on the query role; this file never grows a GET.
 *
 * <ul>
 *   <li>Every POST requires an {@code Idempotency-Key} (UUID). The key is validated here; the
 *       24-hour replay store is P3.2.</li>
 *   <li>A command endpoint returns {@code 202 Accepted} with the resulting resource.</li>
 *   <li>DELETE is a soft delete: it publishes scheduling.shift.deleted.v1 with a tombstone.</li>
 *   <li>Security (JWT + Administrator/Manager) is P1.7 and arrives with the Identity service, because
 *       until Identity publishes its JWKS no token can be validated.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/shifts")
@Profile("command")
public class ShiftController {

    private final ShiftService shifts;

    ShiftController(ShiftService shifts) {
        this.shifts = shifts;
    }

    @PostMapping
    public ResponseEntity<ShiftResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateShiftRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        IdempotencyKeys.requireUuid(idempotencyKey);
        ShiftResponse created = ShiftResponse.from(shifts.create(request.toNewShift(), correlationId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShiftResponse> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateShiftRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        ShiftResponse updated = ShiftResponse.from(shifts.update(id, request.toNewShift(), correlationId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable long id,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        shifts.delete(id, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
