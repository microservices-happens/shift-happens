package dk.ek.shift_happens.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules the replica enforces while consuming workforce.employee.* events. */
class EmployeeReplicaTest {

    @Test
    void anActiveEmployeeIsAssignable() {
        assertThat(employee("ACTIVE", null).isActive()).isTrue();
    }

    @Test
    void anInactiveOrTombstonedEmployeeIsNot() {
        assertThat(employee("INACTIVE", null).isActive()).isFalse();
        assertThat(employee("ACTIVE", Instant.parse("2026-10-01T10:00:00Z")).isActive()).isFalse();
    }

    @Test
    void jobRolesAreMatchedById() {
        EmployeeReplica employee = employee("ACTIVE", null);

        assertThat(employee.hasJobRole(7)).isTrue();
        assertThat(employee.hasJobRole(99)).isFalse();
    }

    @Test
    void anEmployeeWithNoJobRolesMatchesNothing() {
        EmployeeReplica employee = new EmployeeReplica(1, "Ada", "Lovelace", "ACTIVE", null, null, 1, null);

        assertThat(employee.hasJobRole(7)).isFalse();
    }

    @Test
    void onlyAHigherVersionIsAccepted() {
        EmployeeReplica employee = employee("ACTIVE", null);

        assertThat(employee.accepts(2)).isTrue();
        assertThat(employee.accepts(1)).isFalse();
    }

    private static EmployeeReplica employee(String status, Instant deletedAt) {
        return new EmployeeReplica(1, "Ada", "Lovelace", status, List.of(7L), 3L, 1, deletedAt);
    }
}
