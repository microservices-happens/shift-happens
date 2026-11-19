package dk.ek.shift_happens.leave.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** The balance of one employee and leave type right after entry number {@code entryNumber}. */
public record BalanceSnapshot(
        long employeeId,
        long leaveTypeId,
        long entryNumber,
        BigDecimal balanceDays,
        Instant createdAt) {
}
