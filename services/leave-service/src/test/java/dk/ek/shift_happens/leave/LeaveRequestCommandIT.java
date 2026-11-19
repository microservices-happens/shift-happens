package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Submitting, editing and cancelling leave requests: state in Postgres, events in the outbox. */
class LeaveRequestCommandIT extends AbstractCommandIT {

    private static final String SUBMITTED = "leave.request.submitted.v1";
    private static final String CANCELLED = "leave.request.cancelled.v1";

    @Test
    void submittingCreatesAPendingRequestAndASubmittedEventWithTheContractShape() {
        long typeId = createLeaveType("Vacation " + UUID.randomUUID());
        seedEmployee(801);
        String correlationId = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> response = http.postForEntity("/v1/leaverequests",
                new HttpEntity<>(body(801, typeId, "2027-06-01", "2027-06-05", "Summer"),
                        headersFor(UUID.randomUUID().toString(), correlationId)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode created = response.getBody();
        long id = created.path("leaveRequestId").asLong();
        assertThat(created.path("requestStatus").asText()).isEqualTo("PENDING");
        assertThat(created.path("employeeId").asLong()).isEqualTo(801);
        assertThat(instantOf(created.path("requestedDatetime"))).isNotNull();
        assertThat(jdbc.queryForObject("SELECT request_status FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo("PENDING");

        List<JsonNode> events = outboxEvents(SUBMITTED, id);
        assertThat(events).hasSize(1);
        JsonNode envelope = events.get(0);
        assertThat(envelope.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(envelope.path("producer").asText()).isEqualTo("leave-service");
        assertThat(envelope.path("correlationId").asText()).isEqualTo(correlationId);
        JsonNode data = envelope.path("data");
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("leaveRequestId", "employeeId",
                "leaveTypeId", "leaveTypeName", "startDate", "endDate", "requestStatus", "reason",
                "requestedDatetime", "decision", "rejectionReason");
        assertThat(data.path("leaveRequestId").asLong()).isEqualTo(id);
        assertThat(data.path("employeeId").asLong()).isEqualTo(801);
        assertThat(data.path("leaveTypeId").asLong()).isEqualTo(typeId);
        assertThat(data.path("leaveTypeName").asText()).startsWith("Vacation");
        assertThat(LocalDate.parse(data.path("startDate").asText())).isEqualTo(LocalDate.of(2027, 6, 1));
        assertThat(LocalDate.parse(data.path("endDate").asText())).isEqualTo(LocalDate.of(2027, 6, 5));
        assertThat(data.path("requestStatus").asText()).isEqualTo("PENDING");
        assertThat(data.path("reason").asText()).isEqualTo("Summer");
        assertThat(instantOf(data.path("requestedDatetime"))).isNotNull();
        assertThat(data.path("decision").isNull()).isTrue();
        assertThat(data.path("rejectionReason").isNull()).isTrue();
    }

    @Test
    void theSubmittedEventReachesABoundQueueThroughTheRelay() throws Exception {
        long typeId = createLeaveType("Relay " + UUID.randomUUID());
        seedEmployee(802);
        String queue = bindTestQueue("it.leave.submitted." + UUID.randomUUID(), SUBMITTED);

        long id = post("/v1/leaverequests", body(802, typeId, "2027-06-01", "2027-06-02", null), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();

        AtomicReference<JsonNode> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(queue, 300);
            while (message != null) {
                JsonNode envelope = objectMapper.readTree(message.getBody());
                if (envelope.path("aggregateId").asLong() == id) {
                    found.set(envelope);
                    return;
                }
                message = rabbitTemplate.receive(queue, 300);
            }
            assertThat(found.get()).isNotNull();
        });
        assertThat(found.get().path("eventType").asText()).isEqualTo(SUBMITTED);
        assertThat(found.get().path("data").path("requestStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    void anUnknownInactiveOrTombstonedEmployeeIsAConflict() {
        long typeId = createLeaveType("Replica " + UUID.randomUUID());
        seedEmployee(803, "INACTIVE", false);
        seedEmployee(804, "ACTIVE", true);

        for (long employeeId : new long[] {803, 804, 805}) {
            ResponseEntity<String> response = post("/v1/leaverequests",
                    body(employeeId, typeId, "2027-06-01", "2027-06-02", null), String.class);

            assertThat(response.getStatusCode()).as("employee %s", employeeId).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("employee " + employeeId).contains("replica");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id IN (803, 804, 805)",
                Integer.class)).isZero();
    }

    @Test
    void anUnknownLeaveTypeIsABadRequest() {
        seedEmployee(806);

        ResponseEntity<String> response = post("/v1/leaverequests",
                body(806, 99_999_999L, "2027-06-01", "2027-06-02", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("unknown leaveTypeId");
    }

    @Test
    void anEndBeforeTheStartIsABadRequestAndStoresNothing() {
        long typeId = createLeaveType("Dates " + UUID.randomUUID());
        seedEmployee(807);

        ResponseEntity<String> response = post("/v1/leaverequests",
                body(807, typeId, "2027-06-05", "2027-06-01", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id = 807", Integer.class))
                .isZero();
    }

    @Test
    void missingFieldsAndMalformedDatesAreBadRequests() {
        ResponseEntity<String> missing = post("/v1/leaverequests", Map.of("employeeId", 808), String.class);
        ResponseEntity<String> malformed = post("/v1/leaverequests",
                Map.of("employeeId", 808, "leaveTypeId", 1, "startDate", "tomorrow", "endDate", "2027-06-02"),
                String.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).contains("errors");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getHeaders().getContentType().toString()).contains("problem+json");
    }

    @Test
    void anOverlappingRequestIsAConflictButATouchingNeighbourIsNot() {
        long typeId = createLeaveType("Overlap " + UUID.randomUUID());
        seedEmployee(809);
        assertThat(post("/v1/leaverequests", body(809, typeId, "2027-07-01", "2027-07-05", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> sharedLastDay = post("/v1/leaverequests",
                body(809, typeId, "2027-07-05", "2027-07-07", null), String.class);
        ResponseEntity<String> nextDay = post("/v1/leaverequests",
                body(809, typeId, "2027-07-06", "2027-07-07", null), String.class);

        assertThat(sharedLastDay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sharedLastDay.getBody()).contains("already has leave request");
        assertThat(nextDay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void theSameDatesForAnotherEmployeeAreFine() {
        long typeId = createLeaveType("Others " + UUID.randomUUID());
        seedEmployee(810);
        seedEmployee(811);

        assertThat(post("/v1/leaverequests", body(810, typeId, "2027-07-01", "2027-07-05", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post("/v1/leaverequests", body(811, typeId, "2027-07-01", "2027-07-05", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void theDatabaseItselfRefusesOverlappingActiveRequests() {
        long typeId = createLeaveType("Guard " + UUID.randomUUID());
        jdbc.update("INSERT INTO leave_request (employee_id, leave_type_id, start_date, end_date, request_status, "
                + "requested_datetime) VALUES (812, ?, '2027-08-01', '2027-08-05', 'PENDING', now())", typeId);

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO leave_request (employee_id, leave_type_id, start_date, end_date, "
                        + "request_status, requested_datetime) VALUES (812, ?, '2027-08-05', '2027-08-06', "
                        + "'APPROVED', now())", typeId));
        // A cancelled request does not occupy the calendar.
        jdbc.update("INSERT INTO leave_request (employee_id, leave_type_id, start_date, end_date, request_status, "
                + "requested_datetime) VALUES (812, ?, '2027-08-05', '2027-08-06', 'CANCELLED', now())", typeId);
    }

    @Test
    void patchingAPendingRequestRepublishesSubmittedWithAHigherVersion() {
        long typeId = createLeaveType("Patch " + UUID.randomUUID());
        long otherTypeId = createLeaveType("Patched " + UUID.randomUUID());
        seedEmployee(813);
        long id = submit(813, typeId, "2027-09-01", "2027-09-03");

        ResponseEntity<JsonNode> response = patch("/v1/leaverequests/" + id,
                Map.of("leaveTypeId", otherTypeId, "endDate", "2027-09-05", "reason", "longer"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().path("endDate").asText()).isEqualTo("2027-09-05");
        assertThat(response.getBody().path("startDate").asText()).isEqualTo("2027-09-01");
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_request WHERE leave_request_id = ?",
                Long.class, id)).isEqualTo(2L);

        List<JsonNode> events = outboxEvents(SUBMITTED, id);
        assertThat(events).hasSize(2);
        assertThat(events.get(1).path("aggregateVersion").asLong()).isEqualTo(2);
        JsonNode data = events.get(1).path("data");
        assertThat(data.path("leaveTypeId").asLong()).isEqualTo(otherTypeId);
        assertThat(data.path("leaveTypeName").asText()).startsWith("Patched");
        assertThat(data.path("endDate").asText()).isEqualTo("2027-09-05");
        assertThat(data.path("reason").asText()).isEqualTo("longer");
        assertThat(data.path("requestStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    void aPatchCanGrowOverItselfButNotOverAnotherRequest() {
        long typeId = createLeaveType("Grow " + UUID.randomUUID());
        seedEmployee(814);
        long first = submit(814, typeId, "2027-09-01", "2027-09-03");
        submit(814, typeId, "2027-09-10", "2027-09-12");

        assertThat(patch("/v1/leaverequests/" + first, Map.of("endDate", "2027-09-04"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> collision = patch("/v1/leaverequests/" + first, Map.of("endDate", "2027-09-10"),
                JsonNode.class);

        // Title and detail of the application check; the exclusion constraint would answer "Conflicting change".
        assertThat(collision.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(collision.getBody().path("title").asText()).isEqualTo("Leave request rejected");
        assertThat(collision.getBody().path("detail").asText()).contains("already has leave request");
    }

    @Test
    void aPatchWithBadDatesOrUnknownTypeOrIdIsRejected() {
        long typeId = createLeaveType("PatchBad " + UUID.randomUUID());
        seedEmployee(815);
        long id = submit(815, typeId, "2027-09-01", "2027-09-03");

        assertThat(patch("/v1/leaverequests/" + id, Map.of("startDate", "2027-09-09"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch("/v1/leaverequests/" + id, Map.of("leaveTypeId", 99_999_999L), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch("/v1/leaverequests/99999999", Map.of("reason", "x"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_request WHERE leave_request_id = ?",
                Long.class, id)).isEqualTo(1L);
    }

    @Test
    void aNonPendingRequestCannotBePatched() {
        long typeId = createLeaveType("PatchFinal " + UUID.randomUUID());
        seedEmployee(816);
        long id = submit(816, typeId, "2027-09-01", "2027-09-03");
        assertThat(delete("/v1/leaverequests/" + id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> response = patch("/v1/leaverequests/" + id, Map.of("reason", "late"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("CANCELLED");
        assertThat(outboxEvents(SUBMITTED, id)).hasSize(1);
    }

    @Test
    void deletingAPendingRequestCancelsItSoftlyAndPublishesCancelled() {
        long typeId = createLeaveType("Cancel " + UUID.randomUUID());
        seedEmployee(817);
        long id = submit(817, typeId, "2027-10-01", "2027-10-03");

        ResponseEntity<String> response = delete("/v1/leaverequests/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT request_status FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo("CANCELLED");
        List<JsonNode> events = outboxEvents(CANCELLED, id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(events.get(0).path("data").path("requestStatus").asText()).isEqualTo("CANCELLED");
        assertThat(events.get(0).path("data").path("leaveTypeName").asText()).startsWith("Cancel");

        // The freed days can be requested again.
        assertThat(post("/v1/leaverequests", body(817, typeId, "2027-10-01", "2027-10-03", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void cancellingTwiceIsAnIdempotentNoOpAndAMissingRequestIsNotFound() {
        long typeId = createLeaveType("CancelTwice " + UUID.randomUUID());
        seedEmployee(818);
        long id = submit(818, typeId, "2027-10-01", "2027-10-03");
        delete("/v1/leaverequests/" + id);

        // Spec change requested by the product owner (review Q2): a repeated DELETE is a 202 no-op, no longer 409.
        assertThat(delete("/v1/leaverequests/" + id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxEvents(CANCELLED, id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_request WHERE leave_request_id = ?",
                Long.class, id)).isEqualTo(2L);
        assertThat(delete("/v1/leaverequests/99999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void replayingTheSameIdempotencyKeyCreatesOneRequestAndOneEvent() {
        long typeId = createLeaveType("Replay " + UUID.randomUUID());
        seedEmployee(819);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = body(819, typeId, "2027-11-01", "2027-11-02", null);

        ResponseEntity<JsonNode> first = http.postForEntity("/v1/leaverequests",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);
        ResponseEntity<JsonNode> replay = http.postForEntity("/v1/leaverequests",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long id = first.getBody().path("leaveRequestId").asLong();
        assertThat(replay.getBody()).as("a replay returns the first response").isEqualTo(first.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id = 819", Integer.class))
                .isEqualTo(1);
        assertThat(outboxEvents(SUBMITTED, id)).hasSize(1);
    }

    @Test
    void aSameKeyWithADifferentBodyIsAConflictAndCreatesNothing() {
        long typeId = createLeaveType("KeyConflict " + UUID.randomUUID());
        seedEmployee(820);
        String key = UUID.randomUUID().toString();
        assertThat(http.postForEntity("/v1/leaverequests", new HttpEntity<>(
                body(820, typeId, "2027-11-01", "2027-11-02", null), headersFor(key, null)), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<JsonNode> other = http.postForEntity("/v1/leaverequests", new HttpEntity<>(
                body(820, typeId, "2027-12-01", "2027-12-02", null), headersFor(key, null)), JsonNode.class);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(other.getBody().path("title").asText()).isEqualTo("Idempotency-Key reused with a different request");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id = 820", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void anExpiredIdempotencyRecordLetsTheSameKeyRunAgain() {
        long typeId = createLeaveType("Expired " + UUID.randomUUID());
        seedEmployee(821);
        String key = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO idempotency_record (idempotency_key, request_hash, response_status, response_body, "
                + "created_at) VALUES (?, 'stale', 202, '{\"stale\":true}', now() - interval '25 hours')", key);

        ResponseEntity<JsonNode> response = http.postForEntity("/v1/leaverequests", new HttpEntity<>(
                body(821, typeId, "2027-11-01", "2027-11-02", null), headersFor(key, null)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().path("leaveRequestId").asLong()).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id = 821", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM idempotency_record WHERE idempotency_key = ?",
                String.class, key)).isNotEqualTo("stale");
    }

    @Test
    void aKeyThatIsNotAUuidIsABadRequestAndACaseVariantIsTheSameKey() {
        long typeId = createLeaveType("KeyForm " + UUID.randomUUID());
        seedEmployee(822);
        Map<String, Object> body = body(822, typeId, "2027-11-01", "2027-11-02", null);

        ResponseEntity<JsonNode> invalid = http.postForEntity("/v1/leaverequests",
                new HttpEntity<>(body, headersFor("not-a-uuid", null)), JsonNode.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(invalid.getBody().path("title").asText()).isEqualTo("Invalid Idempotency-Key");

        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = http.postForEntity("/v1/leaverequests",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);
        ResponseEntity<JsonNode> upperCase = http.postForEntity("/v1/leaverequests",
                new HttpEntity<>(body, headersFor(key.toUpperCase(), null)), JsonNode.class);

        assertThat(upperCase.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(upperCase.getBody()).isEqualTo(first.getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id = 822", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aFailedCommandStoresNoIdempotencyRecordAndKeepsItsDomainProblem() {
        long typeId = createLeaveType("NoRecord " + UUID.randomUUID());
        seedEmployee(823);
        submit(823, typeId, "2027-11-01", "2027-11-05");
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> overlap = http.postForEntity("/v1/leaverequests", new HttpEntity<>(
                body(823, typeId, "2027-11-03", "2027-11-04", null), headersFor(key, null)), JsonNode.class);

        // The problem is the domain's, not an UnexpectedRollbackException from the surrounding transaction.
        assertThat(overlap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(overlap.getBody().path("title").asText()).isEqualTo("Leave request rejected");
        assertThat(overlap.getBody().path("detail").asText()).contains("already has leave request");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?",
                Integer.class, key)).isZero();
    }

    @Test
    void aPatchThatChangesNothingKeepsTheVersionAndPublishesNothing() {
        long typeId = createLeaveType("Noop " + UUID.randomUUID());
        seedEmployee(824);
        long id = submit(824, typeId, "2027-09-01", "2027-09-03");

        ResponseEntity<JsonNode> empty = patch("/v1/leaverequests/" + id, Map.of(), JsonNode.class);
        ResponseEntity<JsonNode> same = patch("/v1/leaverequests/" + id,
                Map.of("startDate", "2027-09-01", "endDate", "2027-09-03", "leaveTypeId", typeId), JsonNode.class);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(same.getBody().path("leaveRequestId").asLong()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_request WHERE leave_request_id = ?",
                Long.class, id)).isEqualTo(1L);
        assertThat(outboxEvents(SUBMITTED, id)).hasSize(1);
    }

    @Test
    void aRejectedRequestFreesItsDaysForANewSubmission() {
        long typeId = createLeaveType("Resubmit " + UUID.randomUUID());
        seedEmployee(825);
        seedEmployee(826);
        long id = submit(825, typeId, "2027-10-10", "2027-10-12");
        assertThat(post("/v1/leaveapprovals", Map.of("leaveRequestId", id, "approverEmployeeId", 826,
                "decision", "REJECTED"), String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> again = post("/v1/leaverequests",
                body(825, typeId, "2027-10-10", "2027-10-12", null), String.class);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private long submit(long employeeId, long typeId, String start, String end) {
        return post("/v1/leaverequests", body(employeeId, typeId, start, end, null), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();
    }

    private static Map<String, Object> body(long employeeId, long typeId, String start, String end, String reason) {
        return reason == null
                ? Map.of("employeeId", employeeId, "leaveTypeId", typeId, "startDate", start, "endDate", end)
                : Map.of("employeeId", employeeId, "leaveTypeId", typeId, "startDate", start, "endDate", end,
                "reason", reason);
    }
}
