package dk.ek.shift_happens.identity.domain;

/**
 * Who sends a request, taken from the verified JWT: the {@code employeeId} claim and whether the
 * {@code role} claim is Administrator.
 */
public record Caller(long employeeId, boolean administrator) {

    /**
     * contracts/openapi/identity.yaml: an Administrator may set anyone's password, everyone else only
     * their own. Otherwise an employee could take over a colleague's (or an admin's) account.
     */
    public boolean maySetPasswordOf(long targetEmployeeId) {
        return administrator || employeeId == targetEmployeeId;
    }
}
