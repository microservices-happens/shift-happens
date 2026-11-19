package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.domain.TransactionType;
import dk.ek.shift_happens.leave.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The leave-approval saga of leave-command (P3.1) against real PostgreSQL and RabbitMQ. Scheduling is stubbed: the
 * test publishes real reply envelopes to the events exchange (routing key = eventType, aggregateId = leaveRequestId,
 * causationId = the eventId of the approval-started event they answer), exactly as scheduling-service does.
 */
class LeaveApprovalSagaIT extends AbstractSagaIT {

    // ---- 1. approve ----

    @Test
    void approvingStartsTheSagaInOneTransaction() throws Exception {
        long id = scenario(7001, "10");
        Instant before = Instant.now();

        ResponseEntity<JsonNode> response = approve(id, "corr-saga-1");
        Instant after = Instant.now();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().path("decision").asText()).isEqualTo("APPROVED");
        assertThat(response.getBody().path("leaveRequestId").asLong()).isEqualTo(id);
        assertThat(jdbc.queryForMap("SELECT request_status, aggregate_version FROM leave_request "
                + "WHERE leave_request_id = ?", id))
                .containsEntry("request_status", "APPROVAL_PENDING").containsEntry("aggregate_version", 2L);
        assertThat(ledgerRows(7001)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1");

        Map<String, Object> saga = saga(id, 1);
        assertThat(saga).containsEntry("saga_status", "STARTED").containsEntry("correlation_id", "corr-saga-1");
        Instant deadline = ((java.sql.Timestamp) saga.get("deadline")).toInstant();
        assertThat(deadline).as("deadline = start + SAGA_TIMEOUT (default PT5M)")
                .isBetween(before.plus(Duration.ofMinutes(5)), after.plus(Duration.ofMinutes(5)));

        List<JsonNode> started = outboxEvents(STARTED, id);
        assertThat(started).hasSize(1);
        JsonNode envelope = started.get(0);
        assertContractEnvelope(envelope);
        assertThat(uuidOf(envelope.path("eventId")).toString()).isEqualTo(saga.get("approval_started_event_id").toString());
        assertThat(envelope.path("producer").asText()).isEqualTo("leave-service");
        assertThat(envelope.path("correlationId").asText()).isEqualTo("corr-saga-1");
        assertThat(envelope.path("causationId").isNull() || envelope.path("causationId").isMissingNode()).isTrue();
        assertThat(envelope.path("aggregateVersion").asLong()).isEqualTo(2);
        JsonNode data = envelope.path("data");
        assertLeaveRequestSnapshot(data);
        assertThat(data.path("requestStatus").asText()).isEqualTo("APPROVAL_PENDING");
        assertThat(data.path("decision").path("decision").asText()).isEqualTo("APPROVED");
        assertThat(data.path("decision").path("approverEmployeeId").asLong()).isEqualTo(MANAGER);
        assertThat(data.path("decision").path("leaveApprovalId").asLong()).isEqualTo(response.getBody()
                .path("leaveApprovalId").asLong()).isPositive();
        assertThat(data.path("startDate").asText()).isEqualTo("2034-01-10");

        List<JsonNode> recorded = ledgerEvents(id);
        assertThat(recorded).hasSize(1);
        assertContractEnvelope(recorded.get(0));
        assertThat(recorded.get(0).path("correlationId").asText()).isEqualTo("corr-saga-1");
        JsonNode entry = recorded.get(0).path("data");
        assertThat(entry.path("transactionType").asText()).isEqualTo("RESERVATION");
        assertThat(entry.path("changeAmountDays").decimalValue()).isEqualByComparingTo("-3");
        assertThat(entry.path("balanceAfterDays").decimalValue()).isEqualByComparingTo("7");
        assertThat(outboxEvents(APPROVED, id)).isEmpty();
        assertThat(outboxEvents(REJECTED, id)).isEmpty();
    }

    // ---- 2. completed ----

    @Test
    void aCompletedReplyApprovesTheRequest() throws Exception {
        long id = scenario(7002, "10");
        approve(id, "corr-saga-2");
        UUID reply = reply(COMPLETED_REPLY, id, startedEventId(id, 1), null);

        awaitStatus(id, "APPROVED");

        assertThat(saga(id, 1)).containsEntry("saga_status", "COMPLETED");
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_request WHERE leave_request_id = ?",
                Long.class, id)).isEqualTo(3L);
        List<JsonNode> approved = outboxEvents(APPROVED, id);
        assertThat(approved).hasSize(1);
        assertContractEnvelope(approved.get(0));
        assertThat(uuidOf(approved.get(0).path("causationId"))).isEqualTo(reply);
        assertThat(approved.get(0).path("correlationId").asText()).isEqualTo("corr-saga-2");
        assertThat(approved.get(0).path("aggregateVersion").asLong()).isEqualTo(3);
        assertLeaveRequestSnapshot(approved.get(0).path("data"));
        assertThat(approved.get(0).path("data").path("requestStatus").asText()).isEqualTo("APPROVED");
        assertThat(ledgerRows(7002)).as("an approval keeps the reservation").containsExactly("ACCRUAL:10.00:null",
                "RESERVATION:-3.00:1");
        assertThat(outboxEvents(REJECTED, id)).isEmpty();
    }

    // ---- 3. rejected ----

    @Test
    void aRejectedReplyReversesTheReservationAndRejectsTheRequestWithTheReason() throws Exception {
        long id = scenario(7003, "10");
        approve(id, "corr-saga-3");
        UUID reply = reply(REJECTED_REPLY, id, startedEventId(id, 1), "shift 12 (Night) starts within 24 hours");

        awaitStatus(id, "REJECTED");

        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo("shift 12 (Night) starts within 24 hours");
        assertThat(saga(id, 1)).containsEntry("saga_status", "COMPENSATED");
        assertThat(ledgerRows(7003)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1");
        assertThat(balance(7003)).isEqualByComparingTo("10");
        List<JsonNode> rejected = outboxEvents(REJECTED, id);
        assertThat(rejected).hasSize(1);
        assertContractEnvelope(rejected.get(0));
        assertThat(uuidOf(rejected.get(0).path("causationId"))).isEqualTo(reply);
        assertThat(rejected.get(0).path("correlationId").asText()).isEqualTo("corr-saga-3");
        assertThat(rejected.get(0).path("data").path("requestStatus").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get(0).path("data").path("rejectionReason").asText())
                .isEqualTo("shift 12 (Night) starts within 24 hours");
        assertLeaveRequestSnapshot(rejected.get(0).path("data"));
        assertThat(outboxEvents(APPROVED, id)).isEmpty();
        // the reversal's ledger event carries the reply as its cause
        List<JsonNode> recorded = ledgerEvents(id);
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(1).path("data").path("transactionType").asText()).isEqualTo("REVERSAL");
        assertThat(uuidOf(recorded.get(1).path("causationId"))).isEqualTo(reply);
    }

    @Test
    void aRejectedReplyWithoutAReasonGetsAFallbackReason() throws Exception {
        long id = scenario(7004, "10");
        approve(id, null);
        reply(REJECTED_REPLY, id, startedEventId(id, 1), null);

        awaitStatus(id, "REJECTED");

        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isNotBlank();
    }

    // ---- 4. timeout ----

    @Test
    void anOverdueSagaIsCompensatedByTheTimeoutJob() throws Exception {
        long id = scenario(7005, "10");
        approve(id, "corr-saga-5");

        expire(id);

        assertThat(jdbc.queryForMap("SELECT request_status, rejection_reason, aggregate_version FROM leave_request "
                + "WHERE leave_request_id = ?", id))
                .containsEntry("request_status", "REJECTED").containsEntry("rejection_reason", TIMEOUT_REASON)
                .containsEntry("aggregate_version", 3L);
        assertThat(saga(id, 1)).containsEntry("saga_status", "TIMED_OUT");
        assertThat(ledgerRows(7005)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1");
        List<JsonNode> rejected = outboxEvents(REJECTED, id);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).path("causationId").isNull() || rejected.get(0).path("causationId").isMissingNode())
                .as("a timeout has no causing event").isTrue();
        assertThat(rejected.get(0).path("correlationId").asText()).isEqualTo("corr-saga-5");
        assertThat(rejected.get(0).path("data").path("rejectionReason").asText()).isEqualTo(TIMEOUT_REASON);
        assertThat(outboxEvents(APPROVED, id)).isEmpty();
    }

    @Test
    void theScheduledJobCompensatesAnOverdueSagaWithoutHelp() throws Exception {
        long id = scenario(7006, "10");
        approve(id, null);
        makeOverdue(id);

        // app.saga.poll-delay defaults to 15 s
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(saga(id, 1)).containsEntry("saga_status", "TIMED_OUT"));
        assertThat(requestStatus(id)).isEqualTo("REJECTED");
    }

    @Test
    void aSagaThatIsNotOverdueIsLeftAlone() throws Exception {
        long id = scenario(7007, "10");
        approve(id, null);

        for (int i = 0; i < 3; i++) {
            timeoutJob.runOnce();
        }

        assertThat(saga(id, 1)).containsEntry("saga_status", "STARTED");
        assertThat(requestStatus(id)).isEqualTo("APPROVAL_PENDING");
    }

    // ---- 5. late completed after a timeout ----

    @Test
    void aLateCompletedAfterATimeoutReservesAgainAndStartsANewAttempt() throws Exception {
        long id = scenario(7008, "10");
        approve(id, "corr-saga-8");
        UUID firstStart = startedEventId(id, 1);
        expire(id);

        UUID late = reply(COMPLETED_REPLY, id, firstStart, null);
        awaitStatus(id, "APPROVAL_PENDING");

        assertThat(saga(id, 1)).containsEntry("saga_status", "TIMED_OUT");
        Map<String, Object> second = saga(id, 2);
        assertThat(second).containsEntry("saga_status", "STARTED").containsEntry("correlation_id", "corr-saga-8");
        assertThat(((java.sql.Timestamp) second.get("deadline")).toInstant()).isAfter(Instant.now());
        assertThat(ledgerRows(7008)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1",
                "RESERVATION:-3.00:2");
        assertThat(balance(7008)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForMap("SELECT rejection_reason, aggregate_version FROM leave_request "
                + "WHERE leave_request_id = ?", id)).containsEntry("rejection_reason", null)
                .containsEntry("aggregate_version", 4L);
        List<JsonNode> started = outboxEvents(STARTED, id);
        assertThat(started).as("a new approval-started instead of silently approving").hasSize(2);
        JsonNode retry = started.get(1);
        assertContractEnvelope(retry);
        assertThat(uuidOf(retry.path("eventId")).toString()).isEqualTo(second.get("approval_started_event_id").toString());
        assertThat(uuidOf(retry.path("causationId"))).isEqualTo(late);
        assertThat(retry.path("correlationId").asText()).isEqualTo("corr-saga-8");
        assertThat(retry.path("aggregateVersion").asLong()).isEqualTo(4);
        assertThat(retry.path("data").path("requestStatus").asText()).isEqualTo("APPROVAL_PENDING");
        assertThat(outboxEvents(APPROVED, id)).as("the late reply does not approve").isEmpty();

        // Scheduling replays its stored answer for the new approval-started
        UUID replay = reply(COMPLETED_REPLY, id, startedEventId(id, 2), null);
        awaitStatus(id, "APPROVED");
        assertThat(saga(id, 2)).containsEntry("saga_status", "COMPLETED");
        List<JsonNode> approved = outboxEvents(APPROVED, id);
        assertThat(approved).hasSize(1);
        assertThat(uuidOf(approved.get(0).path("causationId"))).isEqualTo(replay);
        assertThat(balance(7008)).isEqualByComparingTo("7");

        // cancelling the approved leave gives back the reservation of the attempt that completed
        assertThat(delete("/v1/leaverequests/" + id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ledgerRows(7008)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1",
                "RESERVATION:-3.00:2", "REVERSAL:3.00:2");
        assertThat(balance(7008)).isEqualByComparingTo("10");
    }

    @Test
    void aLateCompletedWithoutEnoughBalanceLeavesTheRequestRejected() throws Exception {
        long id = scenario(7009, "3");
        approve(id, null);
        UUID firstStart = startedEventId(id, 1);
        expire(id);
        // the reversed days are spent elsewhere in the meantime
        ledger.recordManual(7009, leaveTypeOf(id), TransactionType.ADJUSTMENT, new BigDecimal("-3"), null, null, null);

        UUID late = reply(COMPLETED_REPLY, id, firstStart, null);
        awaitProcessed(late);

        assertThat(requestStatus(id)).isEqualTo("REJECTED");
        assertThat(sagaCount(id)).isEqualTo(1);
        assertThat(outboxEvents(STARTED, id)).hasSize(1);
        assertThat(ledgerRows(7009)).containsExactly("ACCRUAL:3.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1",
                "ADJUSTMENT:-3.00:null");
    }

    @Test
    void theRetryStopsAtTheMaximumNumberOfAttempts() throws Exception {
        long id = scenario(7010, "10");
        approve(id, null);
        for (int attempt = 1; attempt <= 3; attempt++) {
            UUID start = startedEventId(id, attempt);
            expire(id);
            UUID late = reply(COMPLETED_REPLY, id, start, null);
            if (attempt < 3) {
                int next = attempt + 1;
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                        assertThat(sagaCount(id)).as("attempt %d", next).isEqualTo(next));
                awaitStatus(id, "APPROVAL_PENDING");
            } else {
                awaitProcessed(late);
            }
        }

        assertThat(sagaCount(id)).isEqualTo(3);
        assertThat(requestStatus(id)).isEqualTo("REJECTED");
        assertThat(outboxEvents(STARTED, id)).hasSize(3);
        assertThat(balance(7010)).isEqualByComparingTo("10");
        assertThat(outboxEvents(APPROVED, id)).isEmpty();
    }

    // ---- 6. ignored replies ----

    @Test
    void aLateRejectedAfterATimeoutIsIgnored() throws Exception {
        long id = scenario(7011, "10");
        approve(id, null);
        UUID start = startedEventId(id, 1);
        expire(id);
        int ledgerRows = ledgerRows(7011).size();

        UUID late = reply(REJECTED_REPLY, id, start, "too late");
        awaitProcessed(late);

        assertThat(requestStatus(id)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo(TIMEOUT_REASON);
        assertThat(saga(id, 1)).containsEntry("saga_status", "TIMED_OUT");
        assertThat(ledgerRows(7011)).hasSize(ledgerRows);
        assertThat(outboxEvents(REJECTED, id)).as("no second rejected event").hasSize(1);
        assertThat(sagaCount(id)).isEqualTo(1);
    }

    @Test
    void aReplyForACompletedOrCompensatedAttemptIsIgnored() throws Exception {
        long done = scenario(7012, "10");
        approve(done, null);
        reply(COMPLETED_REPLY, done, startedEventId(done, 1), null);
        awaitStatus(done, "APPROVED");
        long refused = scenario(7013, "10");
        approve(refused, null);
        reply(REJECTED_REPLY, refused, startedEventId(refused, 1), "locked");
        awaitStatus(refused, "REJECTED");

        UUID lateRejected = reply(REJECTED_REPLY, done, startedEventId(done, 1), "contradiction");
        UUID lateCompleted = reply(COMPLETED_REPLY, refused, startedEventId(refused, 1), null);
        awaitProcessed(lateRejected);
        awaitProcessed(lateCompleted);

        assertThat(requestStatus(done)).isEqualTo("APPROVED");
        assertThat(requestStatus(refused)).isEqualTo("REJECTED");
        assertThat(ledgerRows(7012)).hasSize(2);
        assertThat(ledgerRows(7013)).hasSize(3);
        assertThat(outboxEvents(REJECTED, done)).isEmpty();
        assertThat(outboxEvents(APPROVED, refused)).isEmpty();
        assertThat(sagaCount(done)).isEqualTo(1);
        assertThat(sagaCount(refused)).isEqualTo(1);
    }

    // ---- 7. duplicates ----

    @Test
    void aDuplicateReplyIsAppliedOnce() throws Exception {
        long id = scenario(7014, "10");
        approve(id, null);
        EventEnvelope completed = replyEnvelope(COMPLETED_REPLY, id, startedEventId(id, 1), null, UUID.randomUUID());

        publish(completed);
        publish(completed);
        publish(completed);
        awaitStatus(id, "APPROVED");
        // a sentinel behind the duplicates proves the queue has drained them
        long other = scenario(7015, "10");
        approve(other, null);
        UUID sentinel = reply(COMPLETED_REPLY, other, startedEventId(other, 1), null);
        awaitProcessed(sentinel);

        assertThat(outboxEvents(APPROVED, id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_request WHERE leave_request_id = ?",
                Long.class, id)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                completed.eventId())).isEqualTo(1);
    }

    @Test
    void aDuplicateRejectedReplyReversesOnce() throws Exception {
        long id = scenario(7016, "10");
        approve(id, null);
        EventEnvelope rejected = replyEnvelope(REJECTED_REPLY, id, startedEventId(id, 1), "locked", UUID.randomUUID());

        publish(rejected);
        publish(rejected);
        awaitStatus(id, "REJECTED");
        long other = scenario(7017, "10");
        approve(other, null);
        awaitProcessed(reply(COMPLETED_REPLY, other, startedEventId(other, 1), null));

        assertThat(ledgerRows(7016)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1");
        assertThat(outboxEvents(REJECTED, id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                rejected.eventId())).isEqualTo(1);
    }

    // ---- 8. correlation ----

    @Test
    void aReplyWithAnUnknownCausationIdIsIgnored() throws Exception {
        long id = scenario(7018, "10");
        long neighbour = scenario(7019, "10");
        approve(id, null);
        approve(neighbour, null);

        UUID unknown = reply(COMPLETED_REPLY, id, UUID.randomUUID(), null);
        UUID wrongRequest = reply(COMPLETED_REPLY, id, startedEventId(neighbour, 1), null);
        awaitProcessed(unknown);
        awaitProcessed(wrongRequest);

        assertThat(requestStatus(id)).isEqualTo("APPROVAL_PENDING");
        assertThat(requestStatus(neighbour)).isEqualTo("APPROVAL_PENDING");
        assertThat(saga(id, 1)).containsEntry("saga_status", "STARTED");
        assertThat(saga(neighbour, 1)).containsEntry("saga_status", "STARTED");
        assertThat(outboxEvents(APPROVED, id)).isEmpty();
        assertThat(outboxEvents(APPROVED, neighbour)).isEmpty();
    }

    @Test
    void aReplyWithoutACausationIdAnswersTheAttemptInFlight() throws Exception {
        long id = scenario(7020, "10");
        approve(id, null);

        UUID reply = reply(COMPLETED_REPLY, id, null, null);

        awaitStatus(id, "APPROVED");
        assertThat(saga(id, 1)).containsEntry("saga_status", "COMPLETED");
        assertThat(uuidOf(outboxEvents(APPROVED, id).get(0).path("causationId"))).isEqualTo(reply);
    }

    @Test
    void aReplyForAnUnknownRequestIsIgnoredAndRecorded() {
        UUID reply = reply(COMPLETED_REPLY, 99_999_999L, UUID.randomUUID(), null);

        awaitProcessed(reply);
    }

    @Test
    void aMalformedReplyIsDeadLettered() {
        UUID eventId = UUID.randomUUID();
        // no leaveRequestId in data: can never be applied
        publish(new EventEnvelope(eventId, COMPLETED_REPLY, Instant.now(), "scheduling-service", 5, 1,
                UUID.randomUUID().toString(), UUID.randomUUID(), Map.of("employeeId", 1)));

        Message dead = awaitDeadLetter(eventId.toString());

        assertThat(dead.getMessageProperties().getReceivedRoutingKey()).isEqualTo("leave.command");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
                eventId)).isZero();
    }

    @Test
    void theQueueIsBoundToExactlyTheEmployeeEventsAndTheTwoReplies() throws IOException {
        JsonNode bindings = managementGet("/api/queues/%2F/leave.command/bindings");
        Set<String> keys = new HashSet<>();
        for (JsonNode binding : bindings) {
            if (binding.path("source").asText().equals(eventsExchange.getName())) {
                keys.add(binding.path("routing_key").asText());
            }
        }

        assertThat(keys).containsExactlyInAnyOrder("workforce.employee.created.v1", "workforce.employee.updated.v1",
                "workforce.employee.deleted.v1", COMPLETED_REPLY, REJECTED_REPLY);
    }

    // ---- 9, 10. refused approvals ----

    @Test
    void approvingWithTooLittleBalanceIsAConflictAndWritesNothing() {
        long id = scenario(7021, "2"); // 3 days wanted
        long ownEvents = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Long.class, id);

        ResponseEntity<JsonNode> response = approve(id, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(response.getBody().path("title").asText()).isEqualTo("Insufficient leave balance");
        assertThat(jdbc.queryForMap("SELECT request_status, aggregate_version FROM leave_request "
                + "WHERE leave_request_id = ?", id))
                .containsEntry("request_status", "PENDING").containsEntry("aggregate_version", 1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_approval WHERE leave_request_id = ?",
                Integer.class, id)).as("no approval row").isZero();
        assertThat(sagaCount(id)).as("no saga").isZero();
        assertThat(ledgerRows(7021)).containsExactly("ACCRUAL:2.00:null");
        assertThat(outboxEvents(STARTED, id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", Long.class, id))
                .isEqualTo(ownEvents);
        assertThat(ledgerEvents(id)).isEmpty();
    }

    @Test
    void approvingARequestThatIsNotPendingIsAStateConflict() {
        long cancelled = scenario(7022, "10");
        delete("/v1/leaverequests/" + cancelled);
        long rejected = scenario(7023, "10");
        decide(rejected, "REJECTED", "no");
        long inFlight = scenario(7024, "10");
        approve(inFlight, null);

        for (long id : new long[] {cancelled, rejected, inFlight}) {
            ResponseEntity<JsonNode> response = approve(id, null);
            assertThat(response.getStatusCode()).as("request %d", id).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().path("title").asText()).isEqualTo("Leave request rejected");
            assertThat(response.getBody().path("detail").asText()).contains(requestStatus(id))
                    .doesNotContain("not available yet");
        }

        assertThat(ledgerRows(7022)).hasSize(1);
        assertThat(ledgerRows(7023)).hasSize(1);
        assertThat(ledgerRows(7024)).as("no second reservation").hasSize(2);
        assertThat(sagaCount(inFlight)).isEqualTo(1);
        assertThat(outboxEvents(STARTED, inFlight)).hasSize(1);
        assertThat(sagaCount(cancelled) + sagaCount(rejected)).isZero();
    }

    @Test
    void approvingForAnInactiveEmployeeIsAConflictAndWritesNothing() {
        long id = scenario(7025, "10");
        jdbc.update("UPDATE employee_replica SET employment_status = 'INACTIVE' WHERE employee_id = 7025");

        ResponseEntity<JsonNode> response = approve(id, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().path("detail").asText()).contains("employee 7025");
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(ledgerRows(7025)).hasSize(1);
        assertThat(sagaCount(id)).isZero();
    }

    @Test
    void anApprovalNeedsAnActiveApprover() {
        long id = scenario(7026, "10");

        ResponseEntity<JsonNode> response = post("/v1/leaveapprovals", Map.of("leaveRequestId", id,
                "approverEmployeeId", 99_999, "decision", "APPROVED"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(requestStatus(id)).isEqualTo("PENDING");
        assertThat(sagaCount(id)).isZero();
    }

    @Test
    void replayingTheApprovalKeyReturnsTheFirstResponseWithoutASecondSaga() {
        long id = scenario(7027, "10");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("leaveRequestId", id, "approverEmployeeId", MANAGER, "decision",
                "APPROVED");

        ResponseEntity<JsonNode> first = http.postForEntity("/v1/leaveapprovals",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);
        ResponseEntity<JsonNode> replay = http.postForEntity("/v1/leaveapprovals",
                new HttpEntity<>(body, headersFor(key, null)), JsonNode.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(sagaCount(id)).isEqualTo(1);
        assertThat(outboxEvents(STARTED, id)).hasSize(1);
        assertThat(ledgerRows(7027)).hasSize(2);
    }

    // ---- 11. cancel ----

    @Test
    void cancellingApprovedLeaveReversesTheReservationAndPublishesCancelled() throws Exception {
        long id = scenario(7028, "10");
        approve(id, null);
        reply(COMPLETED_REPLY, id, startedEventId(id, 1), null);
        awaitStatus(id, "APPROVED");

        ResponseEntity<String> response = http.exchange("/v1/leaverequests/" + id,
                org.springframework.http.HttpMethod.DELETE,
                new HttpEntity<>(headersFor(null, "corr-cancel")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(requestStatus(id)).isEqualTo("CANCELLED");
        assertThat(ledgerRows(7028)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1");
        assertThat(balance(7028)).isEqualByComparingTo("10");
        List<JsonNode> cancelled = outboxEvents(CANCELLED, id);
        assertThat(cancelled).hasSize(1);
        assertContractEnvelope(cancelled.get(0));
        assertThat(cancelled.get(0).path("aggregateVersion").asLong()).isEqualTo(4);
        assertThat(cancelled.get(0).path("data").path("requestStatus").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get(0).path("correlationId").asText()).isEqualTo("corr-cancel");
        assertThat(saga(id, 1)).containsEntry("saga_status", "COMPLETED");

        // a repeated DELETE is a 202 no-op: no second reversal, no second event
        assertThat(delete("/v1/leaverequests/" + id).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ledgerRows(7028)).hasSize(3);
        assertThat(outboxEvents(CANCELLED, id)).hasSize(1);
    }

    @Test
    void cancellingWhileTheSagaIsInFlightIsAConflict() {
        long id = scenario(7029, "10");
        approve(id, null);

        ResponseEntity<String> response = delete("/v1/leaverequests/" + id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("APPROVAL_PENDING");
        assertThat(requestStatus(id)).isEqualTo("APPROVAL_PENDING");
        assertThat(ledgerRows(7029)).hasSize(2);
        assertThat(outboxEvents(CANCELLED, id)).isEmpty();
        assertThat(saga(id, 1)).containsEntry("saga_status", "STARTED");
    }

    @Test
    void aRejectedRequestCannotBeCancelled() throws Exception {
        long id = scenario(7030, "10");
        approve(id, null);
        reply(REJECTED_REPLY, id, startedEventId(id, 1), "locked");
        awaitStatus(id, "REJECTED");

        assertThat(delete("/v1/leaverequests/" + id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outboxEvents(CANCELLED, id)).isEmpty();
    }
}
