package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Manager decisions: REJECTED is final and published, APPROVED is a clear 409 until the saga exists (#47). */
class LeaveApprovalCommandIT extends AbstractCommandIT {

    private static final String REJECTED = "leave.request.rejected.v1";

    @Test
    void rejectingAPendingRequestStoresTheDecisionAndPublishesRejected() {
        long id = pendingRequest(901);
        seedEmployee(990);

        ResponseEntity<JsonNode> response = decide(id, 990, "REJECTED", "Too busy");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode approval = response.getBody();
        assertThat(approval.path("leaveApprovalId").asLong()).isPositive();
        assertThat(approval.path("leaveRequestId").asLong()).isEqualTo(id);
        assertThat(approval.path("decision").asText()).isEqualTo("REJECTED");
        assertThat(approval.path("leaveComment").asText()).isEqualTo("Too busy");
        assertThat(approval.path("decisionDatetime").asText()).isNotBlank();

        assertThat(jdbc.queryForMap("SELECT request_status, rejection_reason, aggregate_version FROM leave_request "
                + "WHERE leave_request_id = ?", id))
                .containsEntry("request_status", "REJECTED")
                .containsEntry("rejection_reason", "Too busy")
                .containsEntry("aggregate_version", 2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_approval WHERE leave_request_id = ?",
                Integer.class, id)).isEqualTo(1);

        List<JsonNode> events = outboxEvents(REJECTED, id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("aggregateVersion").asLong()).isEqualTo(2);
        JsonNode data = events.get(0).path("data");
        assertThat(data.path("requestStatus").asText()).isEqualTo("REJECTED");
        assertThat(data.path("rejectionReason").asText()).isEqualTo("Too busy");
        JsonNode decision = data.path("decision");
        assertThat(decision.path("approverEmployeeId").asLong()).isEqualTo(990);
        assertThat(decision.path("decision").asText()).isEqualTo("REJECTED");
        assertThat(decision.path("comment").asText()).isEqualTo("Too busy");
        assertThat(decision.path("decisionDatetime").asText()).isNotBlank();
    }

    @Test
    void aRejectionWithoutACommentGetsADefaultReason() {
        long id = pendingRequest(902);
        seedEmployee(990);

        assertThat(decide(id, 990, "REJECTED", null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo("Rejected by manager");
    }

    @Test
    void approvingIsNotAvailableYetAndChangesNothing() {
        long id = pendingRequest(903);
        seedEmployee(990);

        ResponseEntity<JsonNode> response = decide(id, 990, "APPROVED", "ok");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(response.getBody().path("title").asText()).isEqualTo("Approval not available yet");
        assertThat(response.getBody().path("detail").asText()).contains("leave-approval saga").contains("P3.1");
        assertThat(jdbc.queryForObject("SELECT request_status FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_approval WHERE leave_request_id = ?",
                Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? "
                + "AND event_type <> 'leave.request.submitted.v1'", Integer.class, id)).isZero();
    }

    @Test
    void aRequestThatIsNotPendingCannotBeRejected() {
        long id = pendingRequest(904);
        seedEmployee(990);
        delete("/v1/leaverequests/" + id);

        ResponseEntity<JsonNode> response = decide(id, 990, "REJECTED", "late");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("detail").asText()).contains("CANCELLED");
        assertThat(outboxEvents(REJECTED, id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_approval WHERE leave_request_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void rejectingTwiceIsAConflictWithOneEvent() {
        long id = pendingRequest(905);
        seedEmployee(990);
        decide(id, 990, "REJECTED", "no");

        assertThat(decide(id, 990, "REJECTED", "again").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEvents(REJECTED, id)).hasSize(1);
    }

    @Test
    void anUnknownRequestIsNotFoundAndABadApproverIsAConflict() {
        long id = pendingRequest(906);
        seedEmployee(990);
        seedEmployee(991, "INACTIVE", false);

        assertThat(decide(99_999_999L, 990, "REJECTED", "x").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> unknownApprover = decide(id, 992, "REJECTED", "x");
        assertThat(unknownApprover.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(unknownApprover.getBody().path("detail").asText()).contains("approver 992");
        assertThat(decide(id, 991, "REJECTED", "x").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT request_status FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo("PENDING");
    }

    @Test
    void anInvalidDecisionOrMissingFieldsIsABadRequest() {
        ResponseEntity<String> badDecision = post("/v1/leaveapprovals",
                Map.of("leaveRequestId", 1, "approverEmployeeId", 1, "decision", "MAYBE"), String.class);
        ResponseEntity<String> missing = post("/v1/leaveapprovals", Map.of("leaveRequestId", 1), String.class);

        assertThat(badDecision.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void replayingAnIdempotencyKeyStoresOneDecisionAndOneEvent() {
        long id = pendingRequest(907);
        seedEmployee(990);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("leaveRequestId", id, "approverEmployeeId", 990, "decision", "REJECTED",
                "leaveComment", "no");

        ResponseEntity<JsonNode> first = http.postForEntity("/v1/leaveapprovals",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);
        ResponseEntity<JsonNode> replay = http.postForEntity("/v1/leaveapprovals",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody().path("leaveApprovalId").asLong())
                .isEqualTo(first.getBody().path("leaveApprovalId").asLong());
        assertThat(outboxEvents(REJECTED, id)).hasSize(1);
    }

    @Test
    void aStoredDecisionIsFinalSoPutIsAConflictOrNotFound() {
        long id = pendingRequest(908);
        seedEmployee(990);
        long approvalId = decide(id, 990, "REJECTED", "no").getBody().path("leaveApprovalId").asLong();
        Map<String, Object> body = Map.of("leaveRequestId", id, "approverEmployeeId", 990, "decision", "REJECTED",
                "leaveComment", "changed my mind");

        ResponseEntity<String> conflict = put("/v1/leaveapprovals/" + approvalId, body, String.class);
        ResponseEntity<String> missing = put("/v1/leaveapprovals/99999999", body, String.class);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("already final");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEvents(REJECTED, id)).hasSize(1);
    }

    private long pendingRequest(long employeeId) {
        long typeId = createLeaveType("Approval " + UUID.randomUUID());
        seedEmployee(employeeId);
        return post("/v1/leaverequests", Map.of("employeeId", employeeId, "leaveTypeId", typeId,
                "startDate", "2028-01-10", "endDate", "2028-01-12"), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();
    }

    private ResponseEntity<JsonNode> decide(long leaveRequestId, long approverId, String decision, String comment) {
        Map<String, Object> body = comment == null
                ? Map.of("leaveRequestId", leaveRequestId, "approverEmployeeId", approverId, "decision", decision)
                : Map.of("leaveRequestId", leaveRequestId, "approverEmployeeId", approverId, "decision", decision,
                "leaveComment", comment);
        return post("/v1/leaveapprovals", body, JsonNode.class);
    }
}
