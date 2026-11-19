package dk.ek.shift_happens.leave.api.dto;

import dk.ek.shift_happens.leave.domain.TransactionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body of POST /v1/leaveledgers (contracts/openapi/leave.yaml, schema LeaveLedgerEntry). Only ACCRUAL and
 * ADJUSTMENT are accepted; the leave ledger id and the timestamp are assigned by the server.
 */
public record CreateLedgerEntryRequest(
        @NotNull @Positive Long employeeId,
        @NotNull @Positive Long leaveTypeId,
        @NotNull BigDecimal changeAmountDays,
        @NotNull TransactionType transactionType,
        @Size(max = 50) String referenceEntityType,
        Long referenceEntityId) {
}
