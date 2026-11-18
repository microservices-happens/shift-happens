package dk.ek.shift_happens.leave.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** data of leave.ledger.entry-recorded.v1 (contracts/events/payloads/ledger-entry.schema.json). */
public record LedgerEntryRecorded(
        long leaveLedgerId,
        long employeeId,
        long leaveTypeId,
        BigDecimal changeAmountDays,
        BigDecimal balanceAfterDays,
        String transactionType,
        String referenceEntityType,
        Long referenceEntityId,
        Instant transactionDatetime) {
}
