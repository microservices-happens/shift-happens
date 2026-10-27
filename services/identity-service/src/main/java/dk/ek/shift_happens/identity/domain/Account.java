package dk.ek.shift_happens.identity.domain;

/**
 * The login account of an employee, projected from workforce.employee.* events.
 *
 * <p>The rules that make the projection safe live here (plain Java, unit-testable):
 * a snapshot only applies if it is newer, and a tombstone always disables the account.
 */
public record Account(
        Long accountId,
        long employeeId,
        String employeeNumber,
        String email,
        String role,
        boolean active,
        long aggregateVersion) {

    public static final String ACTIVE_STATUS = "ACTIVE";

    /** An account created from the first snapshot of an employee. */
    public static Account fromSnapshot(EmployeeSnapshot snapshot, long version) {
        return new Account(null, snapshot.employeeId(), snapshot.employeeNumber(), snapshot.email(),
                snapshot.userRole(), isActive(snapshot), version);
    }

    /** Out-of-order delivery must not change the result: ignore anything older than what we hold. */
    public boolean accepts(long eventVersion) {
        return eventVersion > aggregateVersion;
    }

    public Account withSnapshot(EmployeeSnapshot snapshot, long version) {
        return new Account(accountId, employeeId, snapshot.employeeNumber(), snapshot.email(),
                snapshot.userRole(), isActive(snapshot), version);
    }

    /** workforce.employee.deleted.v1 is a tombstone: the account is disabled, never removed. */
    public Account deleted(long version) {
        return new Account(accountId, employeeId, employeeNumber, email, role, false, version);
    }

    private static boolean isActive(EmployeeSnapshot snapshot) {
        return ACTIVE_STATUS.equalsIgnoreCase(snapshot.employmentStatus());
    }
}
