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
        EmployeeReplica employee = new EmployeeReplica(1, "Ada", "Lovelace", "ACTIVE", null, null, 1, null, 37.5);

        assertThat(employee.hasJobRole(7)).isFalse();
    }

    @Test
    void onlyAHigherVersionIsAccepted() {
        EmployeeReplica employee = employee("ACTIVE", null);

        assertThat(EmployeeReplica.applySnapshot(employee, snapshot("Grace"), 2)).isPresent();
        assertThat(EmployeeReplica.applySnapshot(employee, snapshot("Grace"), 1)).isEmpty();
    }

    @Test
    void aTombstoneWithoutARowIsStoredAndALateCreateDoesNotClearIt() {
        // P3.3: delete (v2) overtakes create (v1).
        Instant deletedAt = Instant.parse("2026-10-01T10:00:00Z");
        EmployeeReplica tombstone = EmployeeReplica.applyTombstone(null, 1, 2, deletedAt).orElseThrow();
        assertThat(tombstone.isActive()).isFalse();
        assertThat(tombstone.snapshotVersion()).isZero();

        EmployeeReplica lateCreate = EmployeeReplica.applySnapshot(tombstone, snapshot("Ada"), 1).orElseThrow();

        assertThat(lateCreate.firstName()).isEqualTo("Ada");
        assertThat(lateCreate.deletedAt()).isEqualTo(deletedAt);
        assertThat(lateCreate.isActive()).isFalse();
        assertThat(lateCreate.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void aSnapshotOlderThanTheTombstoneStillRefreshesTheFieldsOfADeletedEmployee() {
        // P3.3: create v1, delete v3, and only then the update v2 that was published in between.
        Instant deletedAt = Instant.parse("2026-10-01T10:00:00Z");
        EmployeeReplica deleted = EmployeeReplica.applyTombstone(employee("ACTIVE", null), 1, 3, deletedAt)
                .orElseThrow();

        EmployeeReplica refreshed = EmployeeReplica.applySnapshot(deleted, snapshot("Grace"), 2).orElseThrow();

        assertThat(refreshed.firstName()).isEqualTo("Grace");
        assertThat(refreshed.deletedAt()).isEqualTo(deletedAt);
        assertThat(refreshed.aggregateVersion()).isEqualTo(3);
        assertThat(EmployeeReplica.applySnapshot(refreshed, snapshot("Older"), 1)).isEmpty();
    }

    @Test
    void aSnapshotThatIsNotOlderThanTheTombstoneNeverTouchesADeletedEmployee() {
        EmployeeReplica deleted = EmployeeReplica.applyTombstone(employee("ACTIVE", null), 1, 2,
                Instant.parse("2026-10-01T10:00:00Z")).orElseThrow();

        assertThat(EmployeeReplica.applySnapshot(deleted, snapshot("Resurrected"), 3)).isEmpty();
        assertThat(EmployeeReplica.applyTombstone(deleted, 1, 2, Instant.parse("2026-10-02T10:00:00Z")))
                .as("the same tombstone again changes nothing").isEmpty();
    }

    private static EmployeeSnapshot snapshot(String firstName) {
        return new EmployeeSnapshot(1L, "EMP-1", "Employee", firstName, "Lovelace", "ada@shift.dk", null, null,
                "ACTIVE", 3L, List.of(7L), 37.5);
    }

    private static EmployeeReplica employee(String status, Instant deletedAt) {
        return new EmployeeReplica(1, "Ada", "Lovelace", status, List.of(7L), 3L, 1, deletedAt, 37.5);
    }
}
