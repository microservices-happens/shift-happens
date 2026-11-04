package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.api.dto.CreateShiftSwapRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftSwapResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftSwapRequest;
import dk.ek.shift_happens.scheduling.application.IdempotencyService;
import dk.ek.shift_happens.scheduling.application.ShiftSwapService;
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
 * Shift swaps, from contracts/openapi/scheduling.yaml.
 *
 * <p>An employee asks a colleague to take an assignment; a manager decides in
 * {@link ShiftSwapApprovalController}, and an approval moves the assignment in one local transaction.
 * A withdrawal (DELETE) publishes no event, because the event catalog defines no swap deletion.
 */
@RestController
@RequestMapping("/v1/shiftswaps")
@Profile("command")
public class ShiftSwapController {

    private final ShiftSwapService swaps;
    private final IdempotencyService idempotency;

    ShiftSwapController(ShiftSwapService swaps, IdempotencyService idempotency) {
        this.swaps = swaps;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateShiftSwapRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        return idempotency.execute(idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ShiftSwapResponse.from(swaps.request(request.originalShiftAssignmentId(),
                        request.employeeFromId(), request.employeeToId(), request.reason(), correlationId))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ShiftSwapResponse> replace(
            @PathVariable long id,
            @Valid @RequestBody UpdateShiftSwapRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        ShiftSwapResponse updated = ShiftSwapResponse.from(
                swaps.replace(id, request.employeeToId(), request.reason(), correlationId));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        swaps.withdraw(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
