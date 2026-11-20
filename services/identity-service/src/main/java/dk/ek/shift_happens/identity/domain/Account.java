package dk.ek.shift_happens.identity.domain;

/**
 * The login account of an employee, projected from workforce.employee.* events.
 *
 * <p>The rules that make the projection safe live here (plain Java, unit-testable). They are written
 * so the result does not depend on the order the events arrive in (commutative handler, P3.3):
 * <ul>
 *   <li>{@code snapshotVersion} is the version of the snapshot the workforce fields come from; only a
 *       higher one replaces them, also on a deleted account, so the last snapshot before a delete is
 *       kept even when it arrives after the tombstone;</li>
 *   <li>{@code aggregateVersion} is the highest version seen, snapshot or tombstone;</li>
 *   <li>the account is deleted while a tombstone is newer than every snapshot seen
 *       ({@code aggregateVersion > snapshotVersion}), and a deleted account is never active. A
 *       tombstone that arrives before the create is stored as an account without workforce fields
 *       ({@code snapshotVersion} 0), so the late create cannot switch it on.</li>
 * </ul>
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
        long snapshotVersion,
        String passwordHash) {

    public static final String ACTIVE_STATUS = "ACTIVE";
    public static final String ADMINISTRATOR_ROLE = "Administrator";

    /** An account whose workforce fields are as new as its version: the normal, not deleted case. */
    public Account(Long accountId, long employeeId, String employeeNumber, String email, String firstName,
                   String lastName, String role, boolean active, long aggregateVersion, String passwordHash) {
        this(accountId, employeeId, employeeNumber, email, firstName, lastName, role, active, aggregateVersion,
                aggregateVersion, passwordHash);
    }

    /** An account created from the first snapshot of an employee. */
    public static Account fromSnapshot(EmployeeSnapshot snapshot, long version) {
        return new Account(null, snapshot.employeeId(), snapshot.employeeNumber(), snapshot.email(),
                snapshot.firstName(), snapshot.lastName(), snapshot.userRole(), isActive(snapshot), version,
                version, null);
    }

    /**
     * The tombstone of an employee this service has never seen a snapshot of: the delete overtook the
     * create. It holds no workforce fields yet and can never log in.
     */
    public static Account tombstone(long employeeId, long version) {
        return new Account(null, employeeId, null, null, null, null, null, false, version, 0, null);
    }

    /** Out-of-order delivery must not change the result: ignore anything older than what we hold. */
    public boolean accepts(long eventVersion) {
        return eventVersion > aggregateVersion;
    }

    /** A snapshot only replaces the workforce fields of an older snapshot, whatever came in between. */
    public boolean acceptsSnapshot(long snapshotEventVersion) {
        return snapshotEventVersion > snapshotVersion;
    }

    /** A tombstone newer than every snapshot seen so far: workforce.employee.deleted.v1 won. */
    public boolean isDeleted() {
        return aggregateVersion > snapshotVersion;
    }

    public Account withSnapshot(EmployeeSnapshot snapshot, long version) {
        // A tombstone with a higher version keeps winning: the fields are refreshed, the account stays off.
        long highestVersion = Math.max(aggregateVersion, version);
        boolean activeNow = isActive(snapshot) && version == highestVersion;
        // The password hash is owned by identity, so a workforce update never touches it.
        return new Account(accountId, employeeId, snapshot.employeeNumber(), snapshot.email(),
                snapshot.firstName(), snapshot.lastName(), snapshot.userRole(), activeNow, highestVersion,
                version, passwordHash);
    }

    /** workforce.employee.deleted.v1 is a tombstone: the account is disabled, never removed. */
    public Account deleted(long version) {
        return new Account(accountId, employeeId, employeeNumber, email, firstName, lastName, role, false,
                version, snapshotVersion, passwordHash);
    }

    /** Identity owns the password; the hash is bcrypt(pepper + password), made by the caller. */
    public Account withPasswordHash(String newPasswordHash) {
        return new Account(accountId, employeeId, employeeNumber, email, firstName, lastName, role, active,
                aggregateVersion, snapshotVersion, newPasswordHash);
    }

    /**
     * Only an active employee with a password can log in with one. A deactivated or deleted employee
     * keeps the hash (so reactivation restores access) but is refused.
     */
    public boolean canLogInWithPassword() {
        return active && passwordHash != null;
    }

    /**
     * Google/GitHub login (P2.3): being active is enough. The provider has proven the person, so no
     * password is needed, but a deactivated or deleted employee is still refused.
     */
    public boolean canLogInWithProvider() {
        return active;
    }

    private static boolean isActive(EmployeeSnapshot snapshot) {
        return ACTIVE_STATUS.equalsIgnoreCase(snapshot.employmentStatus());
    }
}
