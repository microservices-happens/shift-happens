package dk.ek.shift_happens.workforce.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Domain rules only: no Spring, no database, no broker. */
class EmployeeTest {

    private static NewEmployee validEmployee() {
        return new NewEmployee("EMP-0001", "Employee", "Ada", "Lovelace", "ada@shift.dk",
                "+45 50123456", null, "ACTIVE", 1L);
    }

    @Test
    void newEmployeeStartsAtVersionOne() {
        Employee employee = Employee.create(validEmployee());

        assertThat(employee.id()).isNull();
        assertThat(employee.aggregateVersion()).isEqualTo(1);
        assertThat(employee.employmentStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsAnUnknownRole() {
        NewEmployee unknownRole = new NewEmployee("EMP-0002", "Wizard", "Ada", "Lovelace",
                "wizard@shift.dk", null, null, "ACTIVE", null);

        assertThatThrownBy(() -> Employee.create(unknownRole))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userRole");
    }

    @Test
    void rejectsAMissingEmail() {
        NewEmployee noEmail = new NewEmployee("EMP-0003", "Employee", "Ada", "Lovelace",
                "  ", null, null, "ACTIVE", null);

        assertThatThrownBy(() -> Employee.create(noEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void snapshotNeverContainsSensitiveFields() {
        List<String> fields = Arrays.stream(EmployeeSnapshot.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase())
                .toList();

        assertThat(fields)
                .doesNotContain("salary", "salaryamount", "password", "loginpassword", "passwordhash");
    }
}
