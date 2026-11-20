package dk.ek.shift_happens.identity.api;

/** LoginResponse in contracts/openapi/identity.yaml: the monolith's shape, so the frontend is unchanged. */
public record LoginResponse(
        String token,
        long employeeId,
        String employeeNumber,
        String firstName,
        String lastName,
        String email,
        String roleName) {
}
