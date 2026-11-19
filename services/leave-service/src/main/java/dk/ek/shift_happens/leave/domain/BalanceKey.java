package dk.ek.shift_happens.leave.domain;

/** A balance is kept per employee and leave type. */
public record BalanceKey(long employeeId, long leaveTypeId) {
}
