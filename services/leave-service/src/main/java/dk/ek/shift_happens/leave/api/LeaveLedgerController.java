package dk.ek.shift_happens.leave.api;

import dk.ek.shift_happens.leave.api.dto.CreateLedgerEntryRequest;
import dk.ek.shift_happens.leave.api.dto.LeaveLedgerEntryResponse;
import dk.ek.shift_happens.leave.application.IdempotencyService;
import dk.ek.shift_happens.leave.application.LeaveLedgerService;
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
 * The leave ledger, from contracts/openapi/leave.yaml. Append-only: there is deliberately no PUT or DELETE, and
 * reads belong to the query role (#46).
 */
@RestController
@RequestMapping("/v1/leaveledgers")
@Profile("command")
public class LeaveLedgerController {

    private final LeaveLedgerService ledger;
    private final IdempotencyService idempotency;

    LeaveLedgerController(LeaveLedgerService ledger, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateLedgerEntryRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        return idempotency.execute(idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(LeaveLedgerEntryResponse.from(ledger.recordManual(request.employeeId(), request.leaveTypeId(),
                        request.transactionType(), request.changeAmountDays(), request.referenceEntityType(),
                        request.referenceEntityId(), correlationId))));
    }
}
