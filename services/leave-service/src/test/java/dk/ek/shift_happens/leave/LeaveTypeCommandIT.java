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

/** Leave types: 202 with the resource, leave.type.*.v1 through the outbox, 409 on duplicates, soft delete. */
class LeaveTypeCommandIT extends AbstractCommandIT {

    private static final String CREATED = "leave.type.created.v1";
    private static final String UPDATED = "leave.type.updated.v1";
    private static final String DELETED = "leave.type.deleted.v1";

    @org.springframework.beans.factory.annotation.Autowired
    private dk.ek.shift_happens.leave.application.LeaveLedgerService ledger;

    @Test
    void aTypeIsCreatedWithDefaultsAndPublishesCreatedV1() {
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
        List<JsonNode> created = outboxEvents(CREATED, id);
        assertThat(created).hasSize(1);
        JsonNode event = created.get(0);
        assertThat(event.path("producer").asText()).isEqualTo("leave-service");
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(event.path("data").path("leaveTypeId").asLong()).isEqualTo(id);
        assertThat(event.path("data").path("leaveTypeName").asText()).isEqualTo(name);
        assertThat(event.path("data").path("leaveTypeDescription").isNull()).isTrue();
        assertThat(event.path("data").path("requiresApproval").asBoolean()).isTrue();
        assertThat(event.path("data").path("isPaidLeave").asBoolean()).isTrue();
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
                + "is_paid_leave, aggregate_version FROM leave_type WHERE leave_type_id = ?", id))
                .containsEntry("leave_type_name", renamed)
                .containsEntry("leave_type_description", "No salary")
                .containsEntry("requires_approval", false)
                .containsEntry("is_paid_leave", false)
                .containsEntry("aggregate_version", 2L);
        List<JsonNode> updated = outboxEvents(UPDATED, id);
        assertThat(updated).hasSize(1);
        assertThat(updated.get(0).path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(updated.get(0).path("data").path("leaveTypeName").asText()).isEqualTo(renamed);
        assertThat(updated.get(0).path("data").path("leaveTypeDescription").asText()).isEqualTo("No salary");
        assertThat(updated.get(0).path("data").path("requiresApproval").asBoolean(true)).isFalse();
        assertThat(updated.get(0).path("data").path("isPaidLeave").asBoolean(true)).isFalse();
    }

    @Test
    void aPutThatChangesNothingPublishesNothingAndKeepsTheVersion() {
        String name = "Same " + UUID.randomUUID();
        long id = createLeaveType(name);

        ResponseEntity<JsonNode> response = put("/v1/leavetypes/" + id, Map.of("leaveTypeName", name), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxEvents(UPDATED, id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_type WHERE leave_type_id = ?", Long.class,
                id)).isEqualTo(1L);
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
    void deleteIsSoftPublishesATombstoneAndARepeatedDeleteIsANoOp() {
        long id = createLeaveType("Temp " + UUID.randomUUID());

        ResponseEntity<String> first = delete("/v1/leavetypes/" + id);
        ResponseEntity<String> again = delete("/v1/leavetypes/" + id);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getBody()).isNull();
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForMap("SELECT aggregate_version, deleted_at IS NOT NULL AS gone FROM leave_type "
                + "WHERE leave_type_id = ?", id)).containsEntry("aggregate_version", 2L).containsEntry("gone", true);
        List<JsonNode> tombstones = outboxEvents(DELETED, id);
        assertThat(tombstones).as("no second event for the repeated delete").hasSize(1);
        JsonNode event = tombstones.get(0);
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(event.path("data").path("entityType").asText()).isEqualTo("leaveType");
        assertThat(event.path("data").path("entityId").asLong()).isEqualTo(id);
        assertThat(instantOf(event.path("data").path("deletedAt"))).isNotNull();
    }

    @Test
    void anUnknownTypeIsNotFoundAndADeletedOneCannotBeUpdated() {
        long id = createLeaveType("Gone " + UUID.randomUUID());
        delete("/v1/leavetypes/" + id);

        assertThat(delete("/v1/leavetypes/" + (id + 100_000)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put("/v1/leavetypes/" + id, Map.of("leaveTypeName", "Ghost " + UUID.randomUUID()), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put("/v1/leavetypes/" + (id + 100_000), Map.of("leaveTypeName", "Ghost"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(outboxEvents(UPDATED, id)).isEmpty();
    }

    @Test
    void aTypeUsedByARequestCanBeDeletedAndTheRequestKeepsWorking() {
        long typeId = createLeaveType("Used " + UUID.randomUUID());
        seedEmployee(701);
        long requestId = post("/v1/leaverequests", Map.of("employeeId", 701, "leaveTypeId", typeId,
                "startDate", "2027-05-03", "endDate", "2027-05-04"), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();

        assertThat(delete("/v1/leavetypes/" + typeId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // The existing request is untouched and can still be cancelled; its snapshot keeps the stored type name.
        assertThat(delete("/v1/leaverequests/" + requestId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        List<JsonNode> cancelled = outboxEvents("leave.request.cancelled.v1", requestId);
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0).path("data").path("leaveTypeName").asText()).startsWith("Used ");
        assertThat(jdbc.queryForObject("SELECT request_status FROM leave_request WHERE leave_request_id = ?",
                String.class, requestId)).isEqualTo("CANCELLED");
    }

    @Test
    void aTypeWithLedgerEntriesCanBeDeletedButNoMoreEntriesCanBePosted() {
        long typeId = createLeaveType("Ledgered " + UUID.randomUUID());
        seedEmployee(702);
        ledger.recordManual(702, typeId, dk.ek.shift_happens.leave.domain.TransactionType.ACCRUAL,
                new java.math.BigDecimal("1"), null, null, null);

        assertThat(delete("/v1/leavetypes/" + typeId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<JsonNode> unknown = post("/v1/leaveledgers", Map.of("employeeId", 702, "leaveTypeId",
                typeId + 100_000, "transactionType", "ACCRUAL", "changeAmountDays", 1), JsonNode.class);
        ResponseEntity<JsonNode> deleted = post("/v1/leaveledgers", Map.of("employeeId", 702, "leaveTypeId", typeId,
                "transactionType", "ACCRUAL", "changeAmountDays", 1), JsonNode.class);
        assertThat(deleted.getStatusCode()).as("same answer as an unknown type").isEqualTo(unknown.getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(deleted.getBody().path("type").asText()).isEqualTo(unknown.getBody().path("type").asText());
        assertThat(deleted.getBody().path("detail").asText()).contains("unknown leaveTypeId " + typeId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_ledger WHERE employee_id = 702 "
                + "AND leave_type_id = ?", Integer.class, typeId)).isEqualTo(1);
    }

    @Test
    void aDeletedTypeCannotBeUsedToSubmitOrToMoveARequestToIt() {
        long deletedType = createLeaveType("Dead " + UUID.randomUUID());
        long liveType = createLeaveType("Live " + UUID.randomUUID());
        seedEmployee(703);
        long requestId = post("/v1/leaverequests", Map.of("employeeId", 703, "leaveTypeId", liveType,
                "startDate", "2027-06-07", "endDate", "2027-06-08"), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();
        delete("/v1/leavetypes/" + deletedType);

        ResponseEntity<JsonNode> submit = post("/v1/leaverequests", Map.of("employeeId", 703,
                "leaveTypeId", deletedType, "startDate", "2027-07-05", "endDate", "2027-07-06"), JsonNode.class);
        ResponseEntity<JsonNode> patch = patch("/v1/leaverequests/" + requestId, Map.of("leaveTypeId", deletedType),
                JsonNode.class);

        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit.getBody().path("detail").asText()).contains("unknown leaveTypeId " + deletedType);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch.getBody().path("detail").asText()).contains("unknown leaveTypeId " + deletedType);
        assertThat(jdbc.queryForObject("SELECT leave_type_id FROM leave_request WHERE leave_request_id = ?",
                Long.class, requestId)).isEqualTo(liveType);
    }

    @Test
    void aDeletedTypesNameCanBeReusedButALiveNameCannot() {
        String name = "Reuse " + UUID.randomUUID();
        long first = createLeaveType(name);
        delete("/v1/leavetypes/" + first);

        ResponseEntity<JsonNode> second = post("/v1/leavetypes", Map.of("leaveTypeName", name.toUpperCase()),
                JsonNode.class);
        ResponseEntity<JsonNode> third = post("/v1/leavetypes", Map.of("leaveTypeName", name), JsonNode.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody().path("leaveTypeId").asLong()).isNotEqualTo(first);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
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
