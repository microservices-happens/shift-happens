package dk.ek.shift_happens.workforce.domain;

import java.time.LocalDate;

public final class EmployeeJobRoleDates {

    private EmployeeJobRoleDates() {
    }

    public static void validate(LocalDate assignedDate, LocalDate expiryDate, LocalDate today) {
        if (expiryDate != null && (expiryDate.isBefore(today) || expiryDate.isBefore(assignedDate))) {
            throw new IllegalArgumentException("expiryDate must be on or after today and assignedDate");
        }
    }
}
