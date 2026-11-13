package dk.ek.shift_happens.workforce.domain;

public record EmployeeFilter(String employmentStatus, Long primaryWorkLocationId, String userRole,
                             String email, String firstName, String lastName) {
}
