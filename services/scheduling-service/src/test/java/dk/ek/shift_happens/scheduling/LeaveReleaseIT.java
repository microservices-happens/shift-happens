package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftAssignmentResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Scheduling half of the leave-approval saga (P3.1). Leave is not implemented yet, so the test
 * publishes leave.request.approval-started.v1 exactly as contracts/events/payloads describes it.
 */
class LeaveReleaseIT extends AbstractCommandIT {

    private static final String LEAVE_APPROVAL_STARTED = "leave.request.approval-started.v1";
    private static final String LEAVE_RELEASE_COMPLETED = "scheduling.leave-release.completed.v1";
    private static final String LEAVE_RELEASE_REJECTED = "scheduling.leave-release.rejected.v1";
    private static final String ASSIGNMENT_UPDATED = "scheduling.assignment.updated.v1";

    @Test
    void approvedLeaveReleasesTheOverlappingAssignments() {
        Instant start = Instant.now().plusSeconds(5 * 24 * 3600);
        Long assignmentId = assignedShift(501, start, start.plusSeconds(8 * 3600));
        LocalDate date = LocalDate.ofInstant(start, ZoneOffset.UTC);

        publishApprovalStarted(900, 501, date);

        JsonNode assignment = receiveEvent(ASSIGNMENT_UPDATED, assignmentId);
        assertThat(assignment.path("data").path("assignmentStatus").asText())
                .isEqualTo("RELEASED_FOR_LEAVE");

        JsonNode reply = receiveEvent(LEAVE_RELEASE_COMPLETED, 900);
        assertThat(reply.path("producer").asText()).isEqualTo("scheduling-service");
        assertThat(reply.path("causationId").asText()).isNotBlank();
        assertThat(reply.path("data").path("releasedAssignmentIds").get(0).asLong())
                .isEqualTo(assignmentId);
        assertThat(reply.path("data").path("reason").isNull()).isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT assignment_status FROM shift_assignment WHERE shift_assignment_id = ?", String.class,
                assignmentId)).isEqualTo("RELEASED_FOR_LEAVE");
    }

    @Test
    void aLockedShiftRejectsTheReleaseAndChangesNothing() {
        Instant start = Instant.now().plusSeconds(2 * 3600);
        Long assignmentId = assignedShift(502, start, start.plusSeconds(8 * 3600));
        LocalDate date = LocalDate.ofInstant(start, ZoneOffset.UTC);

        publishApprovalStarted(901, 502, date);

        JsonNode reply = receiveEvent(LEAVE_RELEASE_REJECTED, 901);
        assertThat(reply.path("data").path("reason").asText()).contains("starts within 24 hours");
        assertThat(reply.path("data").path("releasedAssignmentIds")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT assignment_status FROM shift_assignment WHERE shift_assignment_id = ?", String.class,
                assignmentId)).isEqualTo("ASSIGNED");
    }

    @Test
    void aRedeliveredRequestRepublishesTheSameReplyWithoutReleasingTwice() {
        Instant start = Instant.now().plusSeconds(6 * 24 * 3600);
        Long assignmentId = assignedShift(503, start, start.plusSeconds(8 * 3600));
        LocalDate date = LocalDate.ofInstant(start, ZoneOffset.UTC);
        EventEnvelope request = approvalStarted(902, 503, date);

        publish(request);
        assertThat(receiveEvent(LEAVE_RELEASE_COMPLETED, 902).path("data")
                .path("releasedAssignmentIds").get(0).asLong()).isEqualTo(assignmentId);

        long versionAfterFirstReply = jdbc.queryForObject(
                "SELECT aggregate_version FROM shift_assignment WHERE shift_assignment_id = ?", Long.class,
                assignmentId);

        // The orchestrator retries with the same event; the answer is re-published, the state is untouched.
        publish(request);
        assertThat(receiveEvent(LEAVE_RELEASE_COMPLETED, 902).path("data")
                .path("releasedAssignmentIds").get(0).asLong()).isEqualTo(assignmentId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_release WHERE leave_request_id = ?",
                Integer.class, 902L)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT aggregate_version FROM shift_assignment WHERE shift_assignment_id = ?", Long.class,
                assignmentId)).isEqualTo(versionAfterFirstReply);
    }

    private void publishApprovalStarted(long leaveRequestId, long employeeId, LocalDate date) {
        publish(approvalStarted(leaveRequestId, employeeId, date));
    }

    private EventEnvelope approvalStarted(long leaveRequestId, long employeeId, LocalDate date) {
        return new EventEnvelope(UUID.randomUUID(), LEAVE_APPROVAL_STARTED, Instant.now(), "leave-service",
                leaveRequestId, 1, UUID.randomUUID().toString(), null, Map.of(
                        "leaveRequestId", leaveRequestId,
                        "employeeId", employeeId,
                        "leaveTypeId", 1,
                        "leaveTypeName", "Vacation",
                        "startDate", date.toString(),
                        "endDate", date.toString(),
                        "requestStatus", "APPROVAL_PENDING",
                        "requestedDatetime", Instant.now().toString(),
                        "decision", Map.of("approverEmployeeId", 1, "decision", "APPROVED",
                                "decisionDatetime", Instant.now().toString())));
    }

    private Long assignedShift(long employeeId, Instant start, Instant end) {
        Long shiftId = post("/v1/shifts", new CreateShiftRequest(2, 3, "Shift " + employeeId, start, end,
                "PLANNED", List.of(new RequiredJobRoleRequest(7, 1))), ShiftResponse.class).getBody().shiftId();
        publish(new EventEnvelope(UUID.randomUUID(), "workforce.employee.created.v1", Instant.now(),
                "workforce-service", employeeId, 1, UUID.randomUUID().toString(), null, Map.of(
                        "employeeId", employeeId,
                        "employeeNumber", "EMP-" + employeeId,
                        "userRole", "Employee",
                        "firstName", "Ada",
                        "lastName", "Lovelace",
                        "email", "ada" + employeeId + "@shift.dk",
                        "employmentStatus", "ACTIVE",
                        "jobRoleIds", List.of(7L))));
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, employeeId);
        return post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(shiftId, employeeId, "ASSIGNED"), ShiftAssignmentResponse.class)
                .getBody().shiftAssignmentId();
    }
}
