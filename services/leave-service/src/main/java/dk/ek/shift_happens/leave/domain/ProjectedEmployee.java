package dk.ek.shift_happens.leave.domain;

/**
 * What the read model keeps of an employee: the name for the overview and whether the employee was deleted
 * (tombstone). Never salary or contact data, even if an event carried it.
 */
public record ProjectedEmployee(long employeeId, String firstName, String lastName, boolean deleted) {

    /** "first last", or whichever part exists; null when neither is known. */
    public String fullName() {
        String first = firstName == null ? "" : firstName.strip();
        String last = lastName == null ? "" : lastName.strip();
        String name = (first + " " + last).strip();
        return name.isEmpty() ? null : name;
    }
}
