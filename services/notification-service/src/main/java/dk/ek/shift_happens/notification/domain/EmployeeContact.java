package dk.ek.shift_happens.notification.domain;

/**
 * The part of an employee that notification-service needs: the address an email goes to and the role
 * that decides whether the employee is a manager. Kept from {@code workforce.employee.*} events; never
 * read from workforce's database (contracts/README.md).
 */
public record EmployeeContact(long employeeId, String email, String userRole, String employmentStatus, boolean deleted) {

    public static final String MANAGER = "Manager";

    public boolean active() {
        return !deleted && "ACTIVE".equalsIgnoreCase(employmentStatus);
    }

    public boolean manager() {
        return active() && MANAGER.equalsIgnoreCase(userRole);
    }

    /** Only an active employee with a usable address gets email; the in-app notification is always stored. */
    public boolean emailable() {
        return active() && email != null && email.contains("@");
    }
}
