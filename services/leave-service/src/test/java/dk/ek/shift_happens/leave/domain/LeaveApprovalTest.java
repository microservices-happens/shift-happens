package dk.ek.shift_happens.leave.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A stored approval is final; the leave type needs a name. */
class LeaveApprovalTest {

    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    @Test
    void aStoredDecisionCannotBeChanged() {
        LeaveApproval approval = new LeaveApproval(3L, 10, 99, ApprovalDecision.REJECTED, "no", NOW);

        assertThatThrownBy(approval::requireChangeable)
                .isInstanceOf(LeaveRequestRejectedException.class)
                .hasMessageContaining("already final");
    }

    @Test
    void theDecisionObjectOfTheEventComesFromTheApprovalAndCarriesItsId() {
        LeaveApproval approval = new LeaveApproval(3L, 10, 99, ApprovalDecision.REJECTED, "no", NOW);

        assertThat(approval.toDecision())
                .isEqualTo(new LeaveDecision(99, ApprovalDecision.REJECTED, "no", NOW, 3L));
    }

    @Test
    void aLeaveTypeNeedsAName() {
        assertThatThrownBy(() -> new LeaveType(null, " ", null, true, true))
                .isInstanceOf(InvalidLeaveRequestException.class);
    }
}
