package dk.ek.shift_happens.leave.api.dto;

import dk.ek.shift_happens.leave.domain.LedgerEntry;
import dk.ek.shift_happens.leave.domain.LedgerEntryRecorded;

import java.math.BigDecimal;
import java.time.Instant;

/** Response body of POST /v1/leaveledgers (contracts/openapi/leave.yaml, schema LeaveLedgerEntry). */
public record LeaveLedgerEntryResponse(
        long leaveLedgerId,
        long employeeId,
        long leaveTypeId,
        BigDecimal changeAmountDays,
        String transactionType,
        String referenceEntityType,
        Long referenceEntityId,
        Instant transactionDatetime) {

    public static LeaveLedgerEntryResponse from(LedgerEntryRecorded entry) {
        return new LeaveLedgerEntryResponse(entry.leaveLedgerId(), entry.employeeId(), entry.leaveTypeId(),
                entry.changeAmountDays(), entry.transactionType(), entry.referenceEntityType(),
                entry.referenceEntityId(), entry.transactionDatetime());
    }

    public static LeaveLedgerEntryResponse from(LedgerEntry entry) {
        return new LeaveLedgerEntryResponse(entry.leaveLedgerId(), entry.employeeId(), entry.leaveTypeId(),
                entry.changeAmountDays(), entry.transactionType().name(), entry.referenceEntityType(),
                entry.referenceEntityId(), entry.transactionDatetime());
    }
}
