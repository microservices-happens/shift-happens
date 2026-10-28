package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftApprovalRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftApprovalResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftAssignmentResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assignments and approvals (P2.7 + P2.8). The qualification and overlap rules read the replica, which
 * is filled by publishing workforce.employee.* envelopes exactly as contracts/events describes them.
 */
class AssignmentCommandIT extends AbstractCommandIT {

    private static final String ASSIGNMENT_CREATED = "scheduling.assignment.created.v1";
    private static final String ASSIGNMENT_UPDATED = "scheduling.assignment.updated.v1";
    private static final String ASSIGNMENT_DELETED = "scheduling.assignment.deleted.v1";
    private static final Instant START = Instant.parse("2027-01-11T06:00:00Z");
    private static final Instant END = Instant.parse("2027-01-11T14:00:00Z");

    @Test
    void anActiveQualifiedAndFreeEmployeeCanBeAssigned() {
        Long shiftId = createShift(START, END);
        addEmployee(301, 1, "ACTIVE", List.of(7L));

        ResponseEntity<ShiftAssignmentResponse> response = post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(shiftId, 301, "ASSIGNED"), ShiftAssignmentResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        Long assignmentId = response.getBody().shiftAssignmentId();
        assertThat(response.getBody().assignmentStatus()).isEqualTo("ASSIGNED");
        assertThat(response.getBody().assignedDatetime()).isNotNull();

        JsonNode event = receiveEvent(ASSIGNMENT_CREATED, assignmentId);
        JsonNode data = event.path("data");
        assertThat(data.path("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(data.path("employeeId").asLong()).isEqualTo(301);
        assertThat(data.path("assignmentStatus").asText()).isEqualTo("ASSIGNED");
        // The shift window is copied in, so a consumer never has to look the shift up.
        assertThat(data.path("shiftStartDatetime").asText()).isEqualTo("2027-01-11T06:00:00Z");
        assertThat(data.path("shiftEndDatetime").asText()).isEqualTo("2027-01-11T14:00:00Z");
    }

    @Test
    void anEmployeeOutsideTheReplicaIsRejected() {
        Long shiftId = createShift(START, END);

        ResponseEntity<String> response = post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(shiftId, 999_999, "ASSIGNED"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("unknown employee");
    }

    @Test
    void anEmployeeWithoutTheRequiredJobRoleIsRejected() {
        Long shiftId = createShift(START, END);
        addEmployee(302, 1, "ACTIVE", List.of(99L));

        ResponseEntity<String> response = post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(shiftId, 302, "ASSIGNED"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("holds none of the job roles");
    }

    @Test
    void anInactiveEmployeeIsRejected() {
        Long shiftId = createShift(START, END);
        addEmployee(303, 1, "INACTIVE", List.of(7L));

        ResponseEntity<String> response = post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(shiftId, 303, "ASSIGNED"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("is not active");
    }

    @Test
    void anOverlappingShiftIsRejectedButATouchingOneIsNot() {
        addEmployee(304, 1, "ACTIVE", List.of(7L));
        Long morning = createShift(START, END);
        assertThat(post("/v1/shiftassignments", new CreateShiftAssignmentRequest(morning, 304, "ASSIGNED"),
                ShiftAssignmentResponse.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Long overlapping = createShift(Instant.parse("2027-01-11T10:00:00Z"),
                Instant.parse("2027-01-11T18:00:00Z"));
        ResponseEntity<String> rejected = post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(overlapping, 304, "ASSIGNED"), String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody()).contains("overlaps");

        // Half-open intervals: a shift starting exactly when the other ends is allowed.
        Long evening = createShift(END, Instant.parse("2027-01-11T22:00:00Z"));
        assertThat(post("/v1/shiftassignments", new CreateShiftAssignmentRequest(evening, 304, "ASSIGNED"),
                ShiftAssignmentResponse.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void anApprovedApprovalMovesTheAssignmentAndADeletePublishesATombstone() {
        Long shiftId = createShift(START, END);
        addEmployee(305, 1, "ACTIVE", List.of(7L));
        Long assignmentId = post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(shiftId, 305, "ASSIGNED"), ShiftAssignmentResponse.class)
                .getBody().shiftAssignmentId();

        ResponseEntity<ShiftApprovalResponse> approval = post("/v1/shiftapprovals",
                new CreateShiftApprovalRequest(assignmentId, 1, "APPROVED", "ok"),
                ShiftApprovalResponse.class);
        assertThat(approval.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(approval.getBody().decision()).isEqualTo("APPROVED");

        JsonNode approved = receiveEvent(ASSIGNMENT_UPDATED, assignmentId);
        assertThat(approved.path("data").path("assignmentStatus").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("aggregateVersion").asLong()).isEqualTo(2);

        ResponseEntity<String> second = post("/v1/shiftapprovals",
                new CreateShiftApprovalRequest(assignmentId, 1, "REJECTED", "late"), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("already has a decision");

        assertThat(deleteRaw("/v1/shiftassignments/" + assignmentId).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        JsonNode deleted = receiveEvent(ASSIGNMENT_DELETED, assignmentId);
        assertThat(deleted.path("data").path("entityType").asText()).isEqualTo("assignment");
        assertThat(deleted.path("data").path("entityId").asLong()).isEqualTo(assignmentId);

        assertThat(put("/v1/shiftassignments/" + assignmentId,
                new UpdateShiftAssignmentRequest(shiftId, 305, "CANCELLED", null, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Long createShift(Instant start, Instant end) {
        return post("/v1/shifts", new CreateShiftRequest(2, 3, "Shift " + start, start, end, "PLANNED",
                List.of(new RequiredJobRoleRequest(7, 1))), ShiftResponse.class).getBody().shiftId();
    }

    /** Fills the replica the way Workforce would, then waits until the consumer applied it. */
    private void addEmployee(long employeeId, long version, String status, List<Long> jobRoleIds) {
        publish(new EventEnvelope(UUID.randomUUID(), "workforce.employee.created.v1", Instant.now(),
                "workforce-service", employeeId, version, UUID.randomUUID().toString(), null, Map.of(
                        "employeeId", employeeId,
                        "employeeNumber", "EMP-" + employeeId,
                        "userRole", "Employee",
                        "firstName", "Ada",
                        "lastName", "Lovelace",
                        "email", "ada" + employeeId + "@shift.dk",
                        "employmentStatus", status,
                        "jobRoleIds", jobRoleIds)));
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, employeeId);
    }
}
