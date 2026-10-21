package dk.ek.shift_happens.cooperation;

import static org.assertj.core.api.Assertions.fail;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The required system-level cooperation test (P3.1 / P6.5): Leave orchestrates the approval saga
 * and Scheduling releases or rejects the conflicting assignments.
 *
 * <p>Enable when leave-service and scheduling-service implement the saga:
 * <ol>
 *   <li>build both images and add {@code leave-service} and {@code scheduling-service} to
 *       {@code services} input of {@code .github/workflows/system-tests.yml};</li>
 *   <li>start leave-command, scheduling-command, their PostgreSQL databases and RabbitMQ;</li>
 *   <li>submit and approve a leave request through the Leave REST API;</li>
 *   <li>assert the completed path: overlapping assignments are released and
 *       {@code leave.request.approved.v1} is published;</li>
 *   <li>assert the compensation path: a LOCKED shift yields
 *       {@code scheduling.leave-release.rejected.v1}, a REVERSAL ledger entry and a rejected
 *       request.</li>
 * </ol>
 */
class LeaveApprovalSagaIT {

    @Test
    @Disabled("P3.1: enable when leave-service and scheduling-service implement the leave-approval saga (see class Javadoc)")
    void approvedLeaveReleasesOverlappingShiftsAndCompensatesWhenAShiftIsLocked() {
        fail("Not implemented yet");
    }
}
