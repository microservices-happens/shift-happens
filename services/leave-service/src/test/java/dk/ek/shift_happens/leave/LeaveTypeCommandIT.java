package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Leave types are plain CRUD: no events, 202 with the resource, 409 on duplicates and on types in use. */
class LeaveTypeCommandIT extends AbstractCommandIT {

    @Test
    void aTypeIsCreatedWithDefaultsAndPublishesNothing() {
        String name = "Vacation " + UUID.randomUUID();

        ResponseEntity<JsonNode> response = post("/v1/leavetypes", Map.of("leaveTypeName", name), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = response.getBody();
        long id = body.path("leaveTypeId").asLong();
        assertThat(id).isPositive();
        assertThat(body.path("leaveTypeName").asText()).isEqualTo(name);
        assertThat(body.path("requiresApproval").asBoolean()).isTrue();
        assertThat(body.path("isPaidLeave").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT requires_approval AND is_paid_leave FROM leave_type "
                + "WHERE leave_type_id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? "
                + "AND event_type LIKE 'leave.type%'", Integer.class, id)).isZero();
    }

    @Test
    void aTypeCanBeUpdatedWithExplicitFlags() {
        long id = createLeaveType("Unpaid " + UUID.randomUUID());
        String renamed = "Unpaid leave " + UUID.randomUUID();

        ResponseEntity<JsonNode> response = put("/v1/leavetypes/" + id, Map.of("leaveTypeName", renamed,
                "leaveTypeDescription", "No salary", "requiresApproval", false, "isPaidLeave", false),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().path("isPaidLeave").asBoolean(true)).isFalse();
        assertThat(jdbc.queryForMap("SELECT leave_type_name, leave_type_description, requires_approval, "
                + "is_paid_leave FROM leave_type WHERE leave_type_id = ?", id))
                .containsEntry("leave_type_name", renamed)
                .containsEntry("leave_type_description", "No salary")
                .containsEntry("requires_approval", false)
                .containsEntry("is_paid_leave", false);
    }

    @Test
    void aDuplicateNameIsAConflictIgnoringCaseOnCreateAndUpdate() {
        String name = "Sick " + UUID.randomUUID();
        createLeaveType(name);
        long other = createLeaveType("Other " + UUID.randomUUID());

        ResponseEntity<JsonNode> duplicate = post("/v1/leavetypes", Map.of("leaveTypeName", name.toUpperCase()),
                JsonNode.class);
        ResponseEntity<JsonNode> renamed = put("/v1/leavetypes/" + other, Map.of("leaveTypeName", name),
                JsonNode.class);

        // Title and detail of the application check; the unique index would answer "Conflicting change".
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().path("title").asText()).isEqualTo("Leave type conflict");
        assertThat(duplicate.getBody().path("detail").asText()).contains("already exists");
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(renamed.getBody().path("title").asText()).isEqualTo("Leave type conflict");
        assertThat(renamed.getBody().path("detail").asText()).contains("already exists");
    }

    @Test
    void anUpdateKeepingItsOwnNameIsNotADuplicate() {
        String name = "Parental " + UUID.randomUUID();
        long id = createLeaveType(name);

        assertThat(put("/v1/leavetypes/" + id, Map.of("leaveTypeName", name, "leaveTypeDescription", "x"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void anUnusedTypeCanBeDeletedAndAMissingOneIsNotFound() {
        long id = createLeaveType("Temp " + UUID.randomUUID());

        assertThat(delete("/v1/leavetypes/" + id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_type WHERE leave_type_id = ?", Integer.class, id))
                .isZero();
        assertThat(delete("/v1/leavetypes/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put("/v1/leavetypes/" + id, Map.of("leaveTypeName", "Ghost"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTypeUsedByARequestCannotBeDeleted() {
        long typeId = createLeaveType("Used " + UUID.randomUUID());
        seedEmployee(701);
        post("/v1/leaverequests", Map.of("employeeId", 701, "leaveTypeId", typeId, "startDate", "2027-05-03",
                "endDate", "2027-05-04"), JsonNode.class);

        ResponseEntity<String> response = delete("/v1/leavetypes/" + typeId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("used by leave requests");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_type WHERE leave_type_id = ?", Integer.class,
                typeId)).isEqualTo(1);
    }

    @Test
    void aBlankNameIsABadRequest() {
        ResponseEntity<String> response = post("/v1/leavetypes", Map.of("leaveTypeName", " "), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheStoredResponseAndCreatesOneRow() {
        String key = UUID.randomUUID().toString();
        String name = "Replay " + UUID.randomUUID();
        Map<String, Object> body = Map.of("leaveTypeName", name);

        ResponseEntity<JsonNode> first = http.postForEntity("/v1/leavetypes",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);
        ResponseEntity<JsonNode> replay = http.postForEntity("/v1/leavetypes",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody().path("leaveTypeId").asLong()).isEqualTo(first.getBody().path("leaveTypeId").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_type WHERE leave_type_name = ?", Integer.class,
                name)).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        String key = UUID.randomUUID().toString();
        http.postForEntity("/v1/leavetypes",
                new HttpEntity<>(Map.of("leaveTypeName", "Key " + UUID.randomUUID()), headersFor(key, null)),
                JsonNode.class);

        ResponseEntity<String> response = http.postForEntity("/v1/leavetypes",
                new HttpEntity<>(Map.of("leaveTypeName", "Other " + UUID.randomUUID()), headersFor(key, null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("different request body");
    }

    @Test
    void anIdempotencyKeyIsRequiredOnPost() {
        ResponseEntity<String> response = http.postForEntity("/v1/leavetypes",
                new HttpEntity<>(Map.of("leaveTypeName", "NoKey"), headersFor(null, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Idempotency-Key");
    }
}
