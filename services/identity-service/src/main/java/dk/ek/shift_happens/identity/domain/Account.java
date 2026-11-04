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
        String firstName,
        String lastName,
        String role,
        boolean active,
        long aggregateVersion,
        String passwordHash) {

    public static final String ACTIVE_STATUS = "ACTIVE";
    public static final String ADMINISTRATOR_ROLE = "Administrator";

    /** An account created from the first snapshot of an employee. */
    public static Account fromSnapshot(EmployeeSnapshot snapshot, long version) {
        return new Account(null, snapshot.employeeId(), snapshot.employeeNumber(), snapshot.email(),
                snapshot.firstName(), snapshot.lastName(), snapshot.userRole(), isActive(snapshot), version, null);
    }

    /** Out-of-order delivery must not change the result: ignore anything older than what we hold. */
    public boolean accepts(long eventVersion) {
        return eventVersion > aggregateVersion;
    }

    public Account withSnapshot(EmployeeSnapshot snapshot, long version) {
        // The password hash is owned by identity, so a workforce update never touches it.
        return new Account(accountId, employeeId, snapshot.employeeNumber(), snapshot.email(),
                snapshot.firstName(), snapshot.lastName(), snapshot.userRole(), isActive(snapshot), version,
                passwordHash);
    }

    /** workforce.employee.deleted.v1 is a tombstone: the account is disabled, never removed. */
    public Account deleted(long version) {
        return new Account(accountId, employeeId, employeeNumber, email, firstName, lastName, role, false,
                version, passwordHash);
    }

    /** Identity owns the password; the hash is bcrypt(pepper + password), made by the caller. */
    public Account withPasswordHash(String newPasswordHash) {
        return new Account(accountId, employeeId, employeeNumber, email, firstName, lastName, role, active,
                aggregateVersion, newPasswordHash);
    }

    /**
     * Only an active employee with a password can log in with one. A deactivated or deleted employee
     * keeps the hash (so reactivation restores access) but is refused.
     */
    public boolean canLogInWithPassword() {
        return active && passwordHash != null;
    }

    private static boolean isActive(EmployeeSnapshot snapshot) {
        return ACTIVE_STATUS.equalsIgnoreCase(snapshot.employmentStatus());
    }
}
