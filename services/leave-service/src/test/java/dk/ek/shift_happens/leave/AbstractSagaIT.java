package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.application.LeaveLedgerService;
import dk.ek.shift_happens.leave.application.SagaTimeoutJob;
import dk.ek.shift_happens.leave.domain.TransactionType;
import dk.ek.shift_happens.leave.infrastructure.messaging.EventEnvelope;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Helpers shared by the saga integration tests: scenarios, stubbed Scheduling replies, ledger and saga queries. */
abstract class AbstractSagaIT extends AbstractCommandIT {

    static final String STARTED = "leave.request.approval-started.v1";
    static final String APPROVED = "leave.request.approved.v1";
    static final String REJECTED = "leave.request.rejected.v1";
    static final String CANCELLED = "leave.request.cancelled.v1";
    static final String RECORDED = "leave.ledger.entry-recorded.v1";
    static final String COMPLETED_REPLY = "scheduling.leave-release.completed.v1";
    static final String REJECTED_REPLY = "scheduling.leave-release.rejected.v1";
    static final String TIMEOUT_REASON = "Timed out waiting for Scheduling to release the shifts";
    static final long MANAGER = 990;

    @Autowired
    protected LeaveLedgerService ledger;
    @Autowired
    protected SagaTimeoutJob timeoutJob;

    // ---- helpers shared with LeaveApprovalSagaConcurrencyIT ----

    /** A PENDING request of 3 days (2034-01-10..12) for a fresh employee with the given ACCRUAL; returns its id. */
    protected long scenario(long employeeId, String accrual) {
        long typeId = createLeaveType("Saga " + UUID.randomUUID());
        seedEmployee(employeeId);
        seedEmployee(MANAGER);
        ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL, new BigDecimal(accrual), null, null, null);
        return post("/v1/leaverequests", Map.of("employeeId", employeeId, "leaveTypeId", typeId,
                "startDate", "2034-01-10", "endDate", "2034-01-12"), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();
    }

    protected ResponseEntity<JsonNode> approve(long requestId, String correlationId) {
        return http.postForEntity("/v1/leaveapprovals", new HttpEntity<>(Map.of("leaveRequestId", requestId,
                "approverEmployeeId", MANAGER, "decision", "APPROVED", "leaveComment", "enjoy"),
                headersFor(UUID.randomUUID().toString(), correlationId)), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> decide(long requestId, String decision, String comment) {
        return post("/v1/leaveapprovals", Map.of("leaveRequestId", requestId, "approverEmployeeId", MANAGER,
                "decision", decision, "leaveComment", comment), JsonNode.class);
    }

    protected void publish(EventEnvelope envelope) {
        rabbitTemplate.convertAndSend(eventsExchange.getName(), envelope.eventType(), envelope);
    }

    /** Publishes a reply of Scheduling and returns its eventId. */
    protected UUID reply(String eventType, long leaveRequestId, UUID causationId, String reason) {
        EventEnvelope envelope = replyEnvelope(eventType, leaveRequestId, causationId, reason, UUID.randomUUID());
        publish(envelope);
        return envelope.eventId();
    }

    /** The envelope scheduling-service publishes: aggregateVersion 1, aggregateId = leaveRequestId (leave-release.schema.json). */
    protected EventEnvelope replyEnvelope(String eventType, long leaveRequestId, UUID causationId, String reason,
                                          UUID eventId) {
        Map<String, Object> data = new HashMap<>();
        data.put("leaveRequestId", leaveRequestId);
        data.put("employeeId", 1);
        data.put("startDate", "2034-01-10");
        data.put("endDate", "2034-01-12");
        data.put("releasedAssignmentIds", eventType.equals(COMPLETED_REPLY) ? List.of(1, 2) : List.of());
        data.put("reason", reason);
        return new EventEnvelope(eventId, eventType, Instant.now(), "scheduling-service", leaveRequestId, 1,
                UUID.randomUUID().toString(), causationId, data);
    }

    protected void awaitStatus(long requestId, String status) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(requestStatus(requestId)).isEqualTo(status));
    }

    protected void awaitProcessed(UUID eventId) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                        Integer.class, eventId)).isEqualTo(1));
    }

    protected String requestStatus(long requestId) {
        return jdbc.queryForObject("SELECT request_status FROM leave_request WHERE leave_request_id = ?",
                String.class, requestId);
    }

    protected long leaveTypeOf(long requestId) {
        return jdbc.queryForObject("SELECT leave_type_id FROM leave_request WHERE leave_request_id = ?", Long.class,
                requestId);
    }

    protected Map<String, Object> saga(long requestId, int attempt) {
        return jdbc.queryForMap("SELECT * FROM leave_approval_saga WHERE leave_request_id = ? AND attempt = ?",
                requestId, attempt);
    }

    protected int sagaCount(long requestId) {
        return jdbc.queryForObject("SELECT count(*) FROM leave_approval_saga WHERE leave_request_id = ?",
                Integer.class, requestId);
    }

    protected UUID startedEventId(long requestId, int attempt) {
        return (UUID) saga(requestId, attempt).get("approval_started_event_id");
    }

    /** "TYPE:amount:attempt" of every ledger entry of the employee, in order. */
    protected List<String> ledgerRows(long employeeId) {
        return jdbc.queryForList("SELECT transaction_type || ':' || change_amount_days || ':' || "
                + "COALESCE(saga_attempt::text, 'null') FROM leave_ledger WHERE employee_id = ? "
                + "ORDER BY entry_number", String.class, employeeId);
    }

    protected BigDecimal balance(long employeeId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(change_amount_days), 0) FROM leave_ledger "
                + "WHERE employee_id = ?", BigDecimal.class, employeeId);
    }

    /** leave.ledger.entry-recorded.v1 events of ledger entries that reference the request, oldest first. */
    protected List<JsonNode> ledgerEvents(long requestId) {
        return jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = ? "
                        + "AND (payload::jsonb -> 'data' ->> 'referenceEntityId')::bigint = ? "
                        + "AND payload::jsonb -> 'data' ->> 'referenceEntityType' = 'LEAVE_REQUEST' "
                        + "ORDER BY occurred_at, event_id", String.class, RECORDED, requestId).stream()
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    protected void makeOverdue(long requestId) {
        jdbc.update("UPDATE leave_approval_saga SET deadline = now() - interval '1 minute' "
                + "WHERE leave_request_id = ? AND saga_status = 'STARTED'", requestId);
    }

    /** Makes the saga in flight overdue and runs the job until it has timed out (a row lock may skip a round). */
    protected void expire(long requestId) {
        makeOverdue(requestId);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            timeoutJob.runOnce();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_approval_saga WHERE leave_request_id = ? "
                    + "AND saga_status = 'STARTED'", Integer.class, requestId)).isZero();
        });
    }

    protected Message awaitDeadLetter(String marker) {
        AtomicReference<Message> dead = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Message message;
            while ((message = rabbitTemplate.receive("leave.command.dlq", 300)) != null) {
                if (new String(message.getBody(), StandardCharsets.UTF_8).contains(marker)) {
                    dead.set(message);
                    return;
                }
            }
            assertThat(dead.get()).as("a dead letter containing %s", marker).isNotNull();
        });
        return dead.get();
    }

    protected JsonNode managementGet(String path) throws IOException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(RABBIT.getHttpUrl() + path))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                        .encodeToString("shift:shift_dev".getBytes(StandardCharsets.UTF_8)))
                .build();
        try {
            return objectMapper.readTree(client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- contract shape (strict): fields compared with the JSON schemas under contracts/ ----

    private static JsonNode schema(String relativePath) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                    new File("../../contracts/events/" + relativePath));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read contracts/events/" + relativePath, e);
        }
    }

    private static Set<String> names(JsonNode array) {
        Set<String> names = new HashSet<>();
        array.forEach(n -> names.add(n.asText()));
        return names;
    }

    private static Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }

    /** Every envelope has the required fields and no field the envelope schema does not know. */
    protected static void assertContractEnvelope(JsonNode envelope) {
        JsonNode schema = schema("envelope.schema.json");
        assertThat(fieldNames(envelope)).containsAll(names(schema.path("required")))
                .isSubsetOf(fieldNames(schema.path("properties")));
        uuidOf(envelope.path("eventId"));
        instantOf(envelope.path("occurredAt"));
        assertThat(envelope.path("aggregateId").isIntegralNumber()).isTrue();
        assertThat(envelope.path("aggregateVersion").isIntegralNumber()).isTrue();
        assertThat(envelope.path("data").isObject()).isTrue();
    }

    /** data of leave.request.*.v1: required fields present, nothing outside leave-request.schema.json, typed. */
    protected static void assertLeaveRequestSnapshot(JsonNode data) {
        JsonNode schema = schema("payloads/leave-request.schema.json");
        assertThat(fieldNames(data)).containsAll(names(schema.path("required")))
                .isSubsetOf(fieldNames(schema.path("properties")));
        assertThat(names(schema.path("properties").path("requestStatus").path("enum")))
                .contains(data.path("requestStatus").asText());
        LocalDate.parse(data.path("startDate").asText());
        LocalDate.parse(data.path("endDate").asText());
        Instant.parse(data.path("requestedDatetime").asText());
        assertThat(data.path("leaveRequestId").isIntegralNumber()).isTrue();
        assertThat(data.path("employeeId").isIntegralNumber()).isTrue();
        assertThat(data.path("leaveTypeName").isTextual()).isTrue();
        JsonNode decision = data.path("decision");
        if (!decision.isNull()) {
            assertThat(fieldNames(decision)).containsAll(names(schema.path("properties").path("decision")
                    .path("required"))).isSubsetOf(fieldNames(schema.path("properties").path("decision")
                    .path("properties")));
            Instant.parse(decision.path("decisionDatetime").asText());
        }
    }
}
