package dk.ek.shift_happens.leave.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The merge rules of the employee replica, without Spring. */
class EmployeeReplicaTest {

    private static final Instant DELETED_AT = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void aNewerVersionIsApplied() {
        EmployeeReplica current = new EmployeeReplica(1, "Ada", "Lovelace", "ACTIVE", 1, null);

        EmployeeReplica next = EmployeeReplica.applySnapshot(current, snapshot("INACTIVE"), 2).orElseThrow();

        assertThat(next.employmentStatus()).isEqualTo("INACTIVE");
        assertThat(next.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void aSnapshotWithNoRowCreatesIt() {
        assertThat(EmployeeReplica.applySnapshot(null, snapshot("ACTIVE"), 1)).isPresent();
    }

    @Test
    void anOlderOrEqualVersionIsIgnored() {
        EmployeeReplica current = new EmployeeReplica(1, "Ada", "Lovelace", "ACTIVE", 5, null);

        assertThat(EmployeeReplica.applySnapshot(current, snapshot("INACTIVE"), 4)).isEmpty();
        assertThat(EmployeeReplica.applySnapshot(current, snapshot("INACTIVE"), 5)).isEmpty();
    }

    @Test
    void aTombstoneWithNoRowCreatesATombstonedRow() {
        EmployeeReplica next = EmployeeReplica.applyTombstone(null, 1, 3, DELETED_AT).orElseThrow();

        assertThat(next.deletedAt()).isEqualTo(DELETED_AT);
        assertThat(next.aggregateVersion()).isEqualTo(3);
        assertThat(next.isActive()).isFalse();
    }

    @Test
    void aLateCreateAfterAnEarlyTombstoneDoesNotResurrect() {
        EmployeeReplica tombstoned = EmployeeReplica.applyTombstone(null, 1, 3, DELETED_AT).orElseThrow();

        assertThat(EmployeeReplica.applySnapshot(tombstoned, snapshot("ACTIVE"), 1)).isEmpty();
    }

    @Test
    void aTombstoneWithALowerVersionStillTombstonesAndKeepsTheHigherVersion() {
        EmployeeReplica current = new EmployeeReplica(1, "Ada", "Lovelace", "ACTIVE", 5, null);

        EmployeeReplica next = EmployeeReplica.applyTombstone(current, 1, 2, DELETED_AT).orElseThrow();

        assertThat(next.deletedAt()).isEqualTo(DELETED_AT);
        assertThat(next.aggregateVersion()).isEqualTo(5);
        assertThat(next.isActive()).isFalse();
    }

    @Test
    void aNewerSnapshotNeverClearsATombstone() {
        EmployeeReplica current = new EmployeeReplica(1, "Ada", "Lovelace", "ACTIVE", 2, DELETED_AT);

        EmployeeReplica next = EmployeeReplica.applySnapshot(current, snapshot("ACTIVE"), 9).orElseThrow();

        assertThat(next.deletedAt()).isEqualTo(DELETED_AT);
        assertThat(next.aggregateVersion()).isEqualTo(9);
        assertThat(next.isActive()).isFalse();
    }

    @Test
    void aRepeatedTombstoneChangesNothing() {
        EmployeeReplica current = new EmployeeReplica(1, "Ada", "Lovelace", "ACTIVE", 5, DELETED_AT);

        assertThat(EmployeeReplica.applyTombstone(current, 1, 4, Instant.now())).isEmpty();
    }

    @Test
    void isActiveNeedsActiveStatusAndNoTombstone() {
        assertThat(new EmployeeReplica(1, "A", "B", "ACTIVE", 1, null).isActive()).isTrue();
        assertThat(new EmployeeReplica(1, "A", "B", "active", 1, null).isActive()).isTrue();
        assertThat(new EmployeeReplica(1, "A", "B", "INACTIVE", 1, null).isActive()).isFalse();
        assertThat(new EmployeeReplica(1, "A", "B", null, 1, null).isActive()).isFalse();
        assertThat(new EmployeeReplica(1, "A", "B", "ACTIVE", 1, DELETED_AT).isActive()).isFalse();
    }

    private static EmployeeSnapshot snapshot(String status) {
        return new EmployeeSnapshot(1L, "Ada", "Lovelace", status);
    }
}
