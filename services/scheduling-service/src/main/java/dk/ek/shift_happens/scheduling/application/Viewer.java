package dk.ek.shift_happens.scheduling.application;

/**
 * Who is asking, from the JWT of contracts/README.md ({@code employeeId} and {@code role}).
 *
 * <p>The visibility rules of contracts/graphql/scheduling.graphql hang off this record: an Employee sees
 * only their own assignments and swaps, a Manager or Administrator sees everything.
 */
public record Viewer(Long employeeId, String role) {

    public static final String ADMINISTRATOR = "Administrator";
    public static final String MANAGER = "Manager";

    public boolean seesEverything() {
        return ADMINISTRATOR.equals(role) || MANAGER.equals(role);
    }
}
