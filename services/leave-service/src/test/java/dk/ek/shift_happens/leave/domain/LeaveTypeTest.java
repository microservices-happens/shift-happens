package dk.ek.shift_happens.leave.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The versioning and soft-delete rules of a leave type, without Spring. */
class LeaveTypeTest {

    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    private static LeaveType stored() {
        return new LeaveType(7L, "Vacation", "Paid", true, true);
    }

    @Test
    void aNewTypeStartsAtVersionOneAndIsNotDeleted() {
        assertThat(stored().aggregateVersion()).isEqualTo(1);
        assertThat(stored().isDeleted()).isFalse();
    }

    @Test
    void aChangeBumpsTheVersionAndANoOpReturnsTheSameInstance() {
        LeaveType type = stored();

        assertThat(type.changedTo("Vacation", "Paid", true, true)).isSameAs(type);
        LeaveType changed = type.changedTo("Holiday", "Paid", true, true);
        assertThat(changed.aggregateVersion()).isEqualTo(2);
        assertThat(changed.leaveTypeName()).isEqualTo("Holiday");
        assertThat(type.changedTo("Vacation", "Paid", true, false).aggregateVersion()).isEqualTo(2);
        assertThat(type.changedTo("Vacation", null, true, true).aggregateVersion()).isEqualTo(2);
    }

    @Test
    void deletingIsSoftBumpsTheVersionOnceAndKeepsTheName() {
        LeaveType deleted = stored().deleted(NOW);

        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.aggregateVersion()).isEqualTo(2);
        assertThat(deleted.leaveTypeName()).isEqualTo("Vacation");
        assertThat(deleted.deleted(NOW.plusSeconds(60))).as("already deleted: no new version").isSameAs(deleted);
    }

    @Test
    void theEventPayloadsAreTheSnapshotAndTheTombstone() {
        LeaveType type = stored();

        assertThat(type.snapshot()).isEqualTo(new LeaveTypeSnapshot(7, "Vacation", "Paid", true, true));
        assertThat(type.deleted(NOW).tombstone()).isEqualTo(new Tombstone("leaveType", 7, NOW));
        assertThatThrownBy(type::tombstone).isInstanceOf(IllegalStateException.class);
    }
}
