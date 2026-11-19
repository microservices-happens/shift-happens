package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dk.ek.shift_happens.leave.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Acceptance test of the read model (#46). A realistic scenario runs on the COMMAND role through HTTP (types, accruals,
 * adjustments, submits, edits, rejects, approval sagas completed, compensated and timed out, cancels, a leave-type rename
 * and delete, employee updates and a tombstone). Every envelope the command side wrote to its outbox is then fed into a
 * second Spring context in the QUERY role, in the original order, in several seeded shuffles and with duplicates; each
 * time the read model must agree with what the command side's PostgreSQL says (computed by SQL), whatever the order.
 * The same envelopes are validated against the contract schemas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaveProjectionReplayIT extends AbstractSagaIT {

    private static final long GRACE = 8001;
    private static final long ADA = 8002;
    private static final long LINUS = 8003;
    private static final long MIA = 8004;
    private static final long EDITH = 8005;
    private static final String REPLAY_QUEUE = "leave.projection.replay";

    private enum Kind { LONG, TEXT, DATE, INSTANT, DECIMAL, BOOL }

    private static final Map<String, Kind> REQUESTS = spec("leaveRequestId", Kind.LONG, "employeeId", Kind.LONG,
            "leaveTypeId", Kind.LONG, "startDate", Kind.DATE, "endDate", Kind.DATE, "requestStatus", Kind.TEXT,
            "reason", Kind.TEXT, "rejectionReason", Kind.TEXT, "requestedDatetime", Kind.INSTANT);
    private static final Map<String, Kind> APPROVALS = spec("leaveApprovalId", Kind.LONG, "leaveRequestId", Kind.LONG,
            "approverEmployeeId", Kind.LONG, "decision", Kind.TEXT, "leaveComment", Kind.TEXT,
            "decisionDatetime", Kind.INSTANT);
    private static final Map<String, Kind> LEDGER = spec("leaveLedgerId", Kind.LONG, "employeeId", Kind.LONG,
            "leaveTypeId", Kind.LONG, "changeAmountDays", Kind.DECIMAL, "transactionType", Kind.TEXT,
            "referenceEntityType", Kind.TEXT, "referenceEntityId", Kind.LONG, "transactionDatetime", Kind.INSTANT);
    private static final Map<String, Kind> TYPES = spec("leaveTypeId", Kind.LONG, "leaveTypeName", Kind.TEXT,
            "leaveTypeDescription", Kind.TEXT, "requiresApproval", Kind.BOOL, "isPaidLeave", Kind.BOOL);
    private static final Map<String, Kind> OVERVIEW = spec("employeeId", Kind.LONG, "employeeName", Kind.TEXT,
            "leaveRequestId", Kind.LONG, "leaveTypeName", Kind.TEXT, "startDate", Kind.DATE, "endDate", Kind.DATE,
            "requestStatus", Kind.TEXT, "balanceDays", Kind.DECIMAL);

    private final Set<Long> employeeIds = Set.of(GRACE, ADA, LINUS, MIA, EDITH);
    /** Every envelope of the scenario as JSON text: the command side's outbox and the workforce events fed to it. */
    private final List<String> envelopes = new ArrayList<>();
    private final List<String> leaveServiceEnvelopes = new ArrayList<>();
    private final List<Long> typeIds = new ArrayList<>();
    private final List<Long> requestIds = new ArrayList<>();

    private ConfigurableApplicationContext queryRole;
    private MongoTemplate replayMongo;
    private RabbitTemplate replayRabbit;
    private int queryPort;

    // ---- the scenario, on the command role ----

    @BeforeAll
    void runTheScenarioOnTheCommandRole() throws Exception {
        Instant start = Instant.now();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        List<EventEnvelope> workforce = new ArrayList<>();

        workforce.add(employeeEvent(EMPLOYEE_CREATED, GRACE, 1, "Grace", "Hopper"));
        workforce.add(employeeEvent(EMPLOYEE_CREATED, ADA, 1, "Ada", "Lovelace"));
        workforce.add(employeeEvent(EMPLOYEE_CREATED, LINUS, 1, "Linus", "Torvalds"));
        workforce.add(employeeEvent(EMPLOYEE_CREATED, MIA, 1, "Mia", "Manager"));
        workforce.add(employeeEvent(EMPLOYEE_CREATED, EDITH, 1, "Edith", "Clarke"));
        awaitReplicaRows(5);

        long vacation = leaveType("Replay Vacation " + tag);
        long sick = leaveType("Replay Sick " + tag);
        long parental = leaveType("Replay Parental " + tag);
        long temp = leaveType("Replay Temp " + tag);
        assertThat(put("/v1/leavetypes/" + vacation, Map.of("leaveTypeName", "Replay Vacation " + tag),
                String.class).getStatusCode()).as("a no-op PUT").isEqualTo(HttpStatus.ACCEPTED);
        put("/v1/leavetypes/" + temp, Map.of("leaveTypeName", "Replay Temp renamed " + tag,
                "leaveTypeDescription", "short lived"), String.class);

        accrue(GRACE, vacation, "ACCRUAL", "25");
        accrue(GRACE, vacation, "ADJUSTMENT", "-2.5");
        accrue(GRACE, sick, "ACCRUAL", "10");
        accrue(ADA, vacation, "ACCRUAL", "20.75");
        accrue(ADA, temp, "ACCRUAL", "2");
        accrue(LINUS, vacation, "ACCRUAL", "5");
        accrue(EDITH, vacation, "ACCRUAL", "12");
        accrue(EDITH, parental, "ACCRUAL", "3.25");

        long r1 = submit(GRACE, vacation, "2031-01-07", "2031-01-11");
        verdict(r1, "APPROVED", "enjoy");
        reply(COMPLETED_REPLY, r1, startedEventId(r1, 1), null);
        awaitStatus(r1, "APPROVED");

        long r2 = submit(GRACE, sick, "2031-02-03", "2031-02-04");
        verdict(r2, "REJECTED", "not now");

        long r3 = submit(ADA, vacation, "2031-03-03", "2031-03-05");
        assertThat(patch("/v1/leaverequests/" + r3, Map.of("endDate", "2031-03-06", "reason", "longer"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verdict(r3, "APPROVED", null);
        reply(REJECTED_REPLY, r3, startedEventId(r3, 1), "Shift 12 is locked");
        awaitStatus(r3, "REJECTED");

        long r4 = submit(ADA, vacation, "2031-04-07", "2031-04-08");
        verdict(r4, "APPROVED", "ok");
        expire(r4);
        awaitStatus(r4, "REJECTED");

        long r5 = submit(EDITH, vacation, "2031-05-05", "2031-05-09");
        assertThat(delete("/v1/leaverequests/" + r5).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        long r6 = submit(GRACE, vacation, "2031-06-03", "2031-06-04");
        verdict(r6, "APPROVED", null);
        reply(COMPLETED_REPLY, r6, startedEventId(r6, 1), null);
        awaitStatus(r6, "APPROVED");
        assertThat(delete("/v1/leaverequests/" + r6).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        long r7 = submit(LINUS, vacation, "2031-07-01", "2031-07-02");
        long r8 = submit(EDITH, vacation, "2031-08-04", "2031-08-04");
        assertThat(patch("/v1/leaverequests/" + r8, Map.of("leaveTypeId", parental), String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        long r9 = submit(GRACE, sick, "2031-09-01", "2031-09-02");
        long r10 = submit(ADA, temp, "2031-10-06", "2031-10-06");

        // a leave type is renamed, another deleted while a request still uses it, which is cancelled afterwards
        put("/v1/leavetypes/" + sick, Map.of("leaveTypeName", "Replay Sick leave " + tag), String.class);
        put("/v1/leavetypes/" + parental, Map.of("leaveTypeName", "Replay Parental leave " + tag,
                "leaveTypeDescription", "Paid", "requiresApproval", false), String.class);
        assertThat(delete("/v1/leavetypes/" + temp).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(delete("/v1/leavetypes/" + temp).getStatusCode()).as("a repeated delete").isEqualTo(HttpStatus.ACCEPTED);
        assertThat(delete("/v1/leaverequests/" + r10).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        accrue(GRACE, vacation, "ACCRUAL", "1.25");

        workforce.add(employeeEvent(EMPLOYEE_UPDATED, EDITH, 2, "Edith", "Clarke-Smith"));
        workforce.add(employeeEvent(EMPLOYEE_UPDATED, ADA, 2, "Ada", "King"));
        workforce.add(tombstoneEvent(LINUS, 2));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT last_name FROM employee_replica WHERE employee_id = ?",
                    String.class, EDITH)).isEqualTo("Clarke-Smith");
            assertThat(jdbc.queryForObject("SELECT last_name FROM employee_replica WHERE employee_id = ?",
                    String.class, ADA)).isEqualTo("King");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_replica WHERE employee_id = ? "
                    + "AND deleted_at IS NOT NULL", Integer.class, LINUS)).isEqualTo(1);
        });
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE occurred_at >= ? "
                        + "AND published_at IS NULL", Integer.class, Timestamp.from(start))).isZero());

        typeIds.addAll(List.of(vacation, sick, parental, temp));
        requestIds.addAll(List.of(r1, r2, r3, r4, r5, r6, r7, r8, r9, r10));

        List<Map.Entry<Instant, String>> timeline = new ArrayList<>();
        for (String payload : jdbc.queryForList("SELECT payload FROM outbox_event WHERE occurred_at >= ? "
                + "ORDER BY occurred_at, event_id", String.class, Timestamp.from(start))) {
            leaveServiceEnvelopes.add(payload);
            timeline.add(Map.entry(Instant.parse(objectMapper.readTree(payload).path("occurredAt").asText()), payload));
        }
        for (EventEnvelope event : workforce) {
            timeline.add(Map.entry(event.occurredAt(), objectMapper.writeValueAsString(event)));
        }
        timeline.sort(Map.Entry.comparingByKey());
        timeline.forEach(entry -> envelopes.add(entry.getValue()));

        startTheQueryRole();
    }

    @AfterAll
    void stopTheQueryRole() {
        if (queryRole != null) {
            new RabbitAdmin(replayRabbit).deleteQueue(REPLAY_QUEUE);
            new RabbitAdmin(replayRabbit).deleteQueue(REPLAY_QUEUE + ".dlq");
            queryRole.close();
        }
    }

    // ---- the replays ----

    @Test
    void theScenarioProducedEveryKindOfEvent() throws Exception {
        Set<String> types = new HashSet<>();
        for (String payload : leaveServiceEnvelopes) {
            types.add(objectMapper.readTree(payload).path("eventType").asText());
        }

        assertThat(types).containsExactlyInAnyOrder(Events.SUBMITTED, Events.APPROVAL_STARTED, Events.APPROVED,
                Events.REJECTED, Events.CANCELLED, Events.LEDGER, Events.TYPE_CREATED, Events.TYPE_UPDATED,
                Events.TYPE_DELETED);
        assertThat(envelopes.size()).isGreaterThan(40);
    }

    @Test
    void inTheOriginalOrder() throws Exception {
        replayAndCompare(new ArrayList<>(envelopes), "original order");
    }

    @Test
    void inReverseOrder() throws Exception {
        List<String> reversed = new ArrayList<>(envelopes);
        Collections.reverse(reversed);
        replayAndCompare(reversed, "reverse order");
    }

    @Test
    void inSeveralShuffledOrders() throws Exception {
        for (long seed : new long[] {1, 2, 3, 4, 5, 42, 2026}) {
            List<String> shuffled = new ArrayList<>(envelopes);
            Collections.shuffle(shuffled, new Random(seed));
            replayAndCompare(shuffled, "shuffle " + seed);
        }
    }

    @Test
    void withEveryEnvelopeDeliveredTwiceOrMore() throws Exception {
        Random random = new Random(99);
        List<String> noisy = new ArrayList<>();
        for (String envelope : envelopes) {
            for (int copies = 1 + random.nextInt(3); copies > 0; copies--) {
                noisy.add(envelope);
            }
        }
        Collections.shuffle(noisy, random);
        replayAndCompare(noisy, "duplicates");
    }

    // ---- the contract ----

    @Test
    void everyEnvelopeLeaveServicePublishedMatchesTheContractSchemas() throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchema envelopeSchema = schemaFile(factory, config, "envelope.schema.json");
        Map<String, JsonSchema> payloadSchemas = new LinkedHashMap<>();
        for (String file : List.of("leave-request", "ledger-entry", "leave-type", "tombstone")) {
            payloadSchemas.put(file, schemaFile(factory, config, "payloads/" + file + ".schema.json"));
        }

        int snapshotsWithDecision = 0;
        Set<String> checkedTypes = new HashSet<>();
        for (String payload : leaveServiceEnvelopes) {
            JsonNode envelope = objectMapper.readTree(payload);
            String eventType = envelope.path("eventType").asText();
            assertThat(envelopeSchema.validate(envelope)).as("envelope of %s: %s", eventType, payload).isEmpty();
            JsonSchema dataSchema = payloadSchemas.get(payloadSchemaOf(eventType));
            Set<ValidationMessage> errors = dataSchema.validate(envelope.path("data"));
            assertThat(errors).as("data of %s: %s", eventType, payload).isEmpty();
            assertThat(envelope.path("producer").asText()).isEqualTo("leave-service");
            checkedTypes.add(eventType);
            JsonNode decision = envelope.path("data").path("decision");
            if (eventType.startsWith("leave.request.") && decision.isObject()) {
                snapshotsWithDecision++;
                assertThat(decision.path("leaveApprovalId").isIntegralNumber())
                        .as("decision of %s carries leaveApprovalId", payload).isTrue();
            }
            if (eventType.equals(Events.TYPE_DELETED)) {
                assertThat(envelope.path("data").path("entityType").asText()).isEqualTo("leaveType");
            }
        }
        assertThat(snapshotsWithDecision).isGreaterThanOrEqualTo(5);
        assertThat(checkedTypes).hasSize(9);
    }

    private static String payloadSchemaOf(String eventType) {
        if (eventType.startsWith("leave.request.")) {
            return "leave-request";
        }
        if (eventType.equals(Events.LEDGER)) {
            return "ledger-entry";
        }
        return eventType.equals(Events.TYPE_DELETED) ? "tombstone" : "leave-type";
    }

    private JsonSchema schemaFile(JsonSchemaFactory factory, SchemaValidatorsConfig config, String relativePath)
            throws Exception {
        return factory.getSchema(objectMapper.readTree(new java.io.File("../../contracts/events/" + relativePath)),
                config);
    }

    // ---- replay machinery ----

    private void replayAndCompare(List<String> payloads, String label) throws Exception {
        for (String collection : List.of("employees", "leave_types", "leave_requests", "ledger_entries",
                "processed_events")) {
            replayMongo.remove(new Query(), collection);
        }
        Set<String> distinct = new HashSet<>();
        for (String payload : payloads) {
            distinct.add(objectMapper.readTree(payload).path("eventId").asText());
            replayRabbit.send("", REPLAY_QUEUE, MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
                    .setContentType("application/json").build());
        }
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(replayMongo.count(new Query(), "processed_events")).as(label).isEqualTo(distinct.size()));

        long deadLetters = replayRabbit.execute(channel -> channel.messageCount(REPLAY_QUEUE + ".dlq"));
        assertThat(deadLetters).as("%s: nothing dead-lettered", label).isZero();
        compareWithTheCommandDatabase(label);
    }

    private void compareWithTheCommandDatabase(String label) throws Exception {
        String employees = ids(employeeIds);
        String requests = ids(requestIds);
        String types = ids(typeIds);

        List<Map<String, Object>> expectedRequests = fromSql(REQUESTS, """
                SELECT leave_request_id AS "leaveRequestId", employee_id AS "employeeId", leave_type_id AS "leaveTypeId",
                       start_date AS "startDate", end_date AS "endDate", request_status AS "requestStatus",
                       reason AS "reason", rejection_reason AS "rejectionReason", requested_datetime AS "requestedDatetime"
                FROM leave_request WHERE leave_request_id IN (%s) ORDER BY leave_request_id""".formatted(requests));
        assertSame(label + " leave requests", expectedRequests, fromJson(REQUESTS, getJson("/v1/leaverequests")));

        List<Map<String, Object>> expectedApprovals = fromSql(APPROVALS, """
                SELECT leave_approval_id AS "leaveApprovalId", leave_request_id AS "leaveRequestId",
                       approver_employee_id AS "approverEmployeeId", decision AS "decision",
                       leave_comment AS "leaveComment", decision_datetime AS "decisionDatetime"
                FROM leave_approval WHERE leave_request_id IN (%s) ORDER BY leave_approval_id""".formatted(requests));
        assertThat(expectedApprovals).as("the scenario has decisions").hasSizeGreaterThanOrEqualTo(5);
        assertSame(label + " leave approvals", expectedApprovals, fromJson(APPROVALS, getJson("/v1/leaveapprovals")));
        for (Map<String, Object> approval : expectedApprovals) {
            assertSame(label + " approval by id", List.of(approval),
                    fromJson(APPROVALS, objectMapper.createArrayNode().add(getJson("/v1/leaveapprovals/"
                            + approval.get("leaveApprovalId")))));
            assertSame(label + " approvals of request", List.of(approval), fromJson(APPROVALS,
                    getJson("/v1/leaveapprovals/request/" + approval.get("leaveRequestId"))));
        }

        assertSame(label + " ledger", fromSql(LEDGER, """
                SELECT leave_ledger_id AS "leaveLedgerId", employee_id AS "employeeId", leave_type_id AS "leaveTypeId",
                       change_amount_days AS "changeAmountDays", transaction_type AS "transactionType",
                       reference_entity_type AS "referenceEntityType", reference_entity_id AS "referenceEntityId",
                       transaction_datetime AS "transactionDatetime"
                FROM leave_ledger WHERE employee_id IN (%s) ORDER BY leave_ledger_id""".formatted(employees)),
                fromJson(LEDGER, getJson("/v1/leaveledgers")));

        assertSame(label + " leave types", fromSql(TYPES, """
                SELECT leave_type_id AS "leaveTypeId", leave_type_name AS "leaveTypeName",
                       leave_type_description AS "leaveTypeDescription", requires_approval AS "requiresApproval",
                       is_paid_leave AS "isPaidLeave"
                FROM leave_type WHERE leave_type_id IN (%s) AND deleted_at IS NULL ORDER BY leave_type_id"""
                .formatted(types)), fromJson(TYPES, getJson("/v1/leavetypes")));

        // The monolith's view joined the employee table: a deleted employee has no rows; the balance is SUM(change).
        List<Map<String, Object>> expectedOverview = fromSql(OVERVIEW, """
                SELECT r.employee_id AS "employeeId", e.first_name || ' ' || e.last_name AS "employeeName",
                       r.leave_request_id AS "leaveRequestId", t.leave_type_name AS "leaveTypeName",
                       r.start_date AS "startDate", r.end_date AS "endDate", r.request_status AS "requestStatus",
                       COALESCE((SELECT SUM(l.change_amount_days) FROM leave_ledger l
                                 WHERE l.employee_id = r.employee_id AND l.leave_type_id = r.leave_type_id), 0) AS "balanceDays"
                FROM leave_request r
                JOIN employee_replica e ON e.employee_id = r.employee_id
                JOIN leave_type t ON t.leave_type_id = r.leave_type_id
                WHERE e.deleted_at IS NULL AND r.leave_request_id IN (%s)
                ORDER BY r.employee_id, r.start_date, r.leave_request_id""".formatted(requests));
        assertThat(expectedOverview).as("the deleted employee's request is not in the view")
                .hasSize(requestIds.size() - 1);
        assertSame(label + " overview", expectedOverview, fromJson(OVERVIEW,
                getJson("/v1/views/employee-leave-overview")));
        for (long employeeId : employeeIds) {
            assertSame(label + " overview of " + employeeId, filter(expectedOverview, "employeeId", employeeId),
                    fromJson(OVERVIEW, getJson("/v1/views/employee-leave-overview/employee/" + employeeId)));
        }
        for (String status : List.of("PENDING", "APPROVAL_PENDING", "APPROVED", "REJECTED", "CANCELLED")) {
            assertSame(label + " overview " + status, filter(expectedOverview, "requestStatus", status),
                    fromJson(OVERVIEW, getJson("/v1/views/employee-leave-overview/status/" + status.toLowerCase())));
        }
        for (String name : expectedOverview.stream().map(row -> (String) row.get("leaveTypeName")).distinct().toList()) {
            List<Map<String, Object>> expected = expectedOverview.stream()
                    .filter(row -> name.equalsIgnoreCase((String) row.get("leaveTypeName"))).toList();
            assertSame(label + " overview of type " + name, expected, fromJson(OVERVIEW,
                    getJson("/v1/views/employee-leave-overview/leave-type/" + name.toUpperCase())));
        }
    }

    private void startTheQueryRole() {
        queryRole = new SpringApplicationBuilder(dk.ek.shift_happens.leave.LeaveServiceApplication.class).run(
                "--spring.profiles.active=query",
                "--server.port=0",
                "--spring.data.mongodb.host=" + AbstractQueryIT.MONGO.getHost(),
                "--spring.data.mongodb.port=" + AbstractQueryIT.MONGO.getMappedPort(27017),
                "--spring.data.mongodb.database=leave_read_replay",
                "--spring.data.mongodb.username=" + AbstractQueryIT.MONGO_USER,
                "--spring.data.mongodb.password=" + AbstractQueryIT.MONGO_PASSWORD,
                "--spring.data.mongodb.authentication-database=admin",
                "--spring.rabbitmq.host=" + AbstractQueryIT.RABBIT.getHost(),
                "--spring.rabbitmq.port=" + AbstractQueryIT.RABBIT.getAmqpPort(),
                "--spring.rabbitmq.username=shift",
                "--spring.rabbitmq.password=shift_dev",
                "--spring.rabbitmq.virtual-host=/",
                "--app.rabbit.queue=" + REPLAY_QUEUE);
        replayMongo = queryRole.getBean(MongoTemplate.class);
        replayRabbit = queryRole.getBean(RabbitTemplate.class);
        queryPort = ((ServletWebServerApplicationContext) queryRole).getWebServer().getPort();
    }

    private JsonNode getJson(String path) throws Exception {
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + queryPort + path
                        .replace(" ", "%20"))).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s: %s", path, response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    // ---- scenario helpers ----

    private EventEnvelope employeeEvent(String eventType, long employeeId, long version, String first, String last) {
        Map<String, Object> data = new LinkedHashMap<>(Events.employee(employeeId, first, last));
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(), "workforce-service",
                employeeId, version, UUID.randomUUID().toString(), null, data);
        publish(envelope);
        return envelope;
    }

    private EventEnvelope tombstoneEvent(long employeeId, long version) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), EMPLOYEE_DELETED, Instant.now(),
                "workforce-service", employeeId, version, UUID.randomUUID().toString(), null,
                Events.tombstone("employee", employeeId));
        publish(envelope);
        return envelope;
    }

    private static final String EMPLOYEE_CREATED = Events.EMPLOYEE_CREATED;
    private static final String EMPLOYEE_UPDATED = Events.EMPLOYEE_UPDATED;
    private static final String EMPLOYEE_DELETED = Events.EMPLOYEE_DELETED;

    private void awaitReplicaRows(int expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_replica WHERE employee_id IN (" + ids(employeeIds)
                        + ")", Integer.class)).isEqualTo(expected));
    }

    private long leaveType(String name) {
        return createLeaveType(name);
    }

    private void accrue(long employeeId, long typeId, String transactionType, String amount) {
        ResponseEntity<JsonNode> response = post("/v1/leaveledgers", Map.of("employeeId", employeeId,
                "leaveTypeId", typeId, "transactionType", transactionType, "changeAmountDays", new BigDecimal(amount)),
                JsonNode.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.ACCEPTED);
    }

    private long submit(long employeeId, long typeId, String start, String end) {
        ResponseEntity<JsonNode> response = post("/v1/leaverequests", Map.of("employeeId", employeeId,
                "leaveTypeId", typeId, "startDate", start, "endDate", end, "reason", "replay"), JsonNode.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().path("leaveRequestId").asLong();
    }

    private void verdict(long requestId, String decision, String comment) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("leaveRequestId", requestId,
                "approverEmployeeId", MIA, "decision", decision));
        if (comment != null) {
            body.put("leaveComment", comment);
        }
        ResponseEntity<JsonNode> response = post("/v1/leaveapprovals", body, JsonNode.class);
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ---- comparison helpers ----

    private static Map<String, Kind> spec(Object... pairs) {
        Map<String, Kind> spec = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            spec.put((String) pairs[i], (Kind) pairs[i + 1]);
        }
        return spec;
    }

    private static String ids(java.util.Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Map<String, Object>> fromSql(Map<String, Kind> spec, String sql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.queryForList(sql)) {
            Map<String, Object> row = new LinkedHashMap<>();
            spec.forEach((field, kind) -> row.put(field, fromSqlValue(raw.get(field), kind)));
            rows.add(row);
        }
        return rows;
    }

    private static Object fromSqlValue(Object value, Kind kind) {
        if (value == null) {
            return null;
        }
        return switch (kind) {
            case LONG -> ((Number) value).longValue();
            case TEXT -> value.toString();
            case DATE -> ((java.sql.Date) value).toLocalDate();
            case INSTANT -> ((Timestamp) value).toInstant();
            case DECIMAL -> ((BigDecimal) value).setScale(2);
            case BOOL -> (Boolean) value;
        };
    }

    private List<Map<String, Object>> fromJson(Map<String, Kind> spec, JsonNode array) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode node : array) {
            Map<String, Object> row = new LinkedHashMap<>();
            spec.forEach((field, kind) -> row.put(field, fromJsonValue(node.get(field), kind)));
            rows.add(row);
        }
        return rows;
    }

    private static Object fromJsonValue(JsonNode node, Kind kind) {
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (kind) {
            case LONG -> node.asLong();
            case TEXT -> node.asText();
            case DATE -> LocalDate.parse(node.asText());
            case INSTANT -> Instant.parse(node.asText());
            case DECIMAL -> node.decimalValue().setScale(2);
            case BOOL -> node.asBoolean();
        };
    }

    private static List<Map<String, Object>> filter(List<Map<String, Object>> rows, String field, Object value) {
        return rows.stream().filter(row -> value.equals(row.get(field))).toList();
    }

    /**
     * Same rows in the same order. Instants are compared to the microsecond: PostgreSQL stores timestamps in
     * microseconds, while an event carries the nanoseconds of the JVM clock.
     */
    private static void assertSame(String label, List<Map<String, Object>> expected, List<Map<String, Object>> actual) {
        assertThat(actual).as("%s: number of rows", label).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            for (Map.Entry<String, Object> field : expected.get(i).entrySet()) {
                Object want = field.getValue();
                Object got = actual.get(i).get(field.getKey());
                String where = label + ", row " + i + ", " + field.getKey() + "\nexpected row " + expected.get(i)
                        + "\n  actual row " + actual.get(i);
                if (want instanceof Instant wantInstant && got instanceof Instant gotInstant) {
                    assertThat(Duration.between(wantInstant, gotInstant).abs()).as(where)
                            .isLessThanOrEqualTo(Duration.ofNanos(1_000));
                } else {
                    assertThat(got).as(where).isEqualTo(want);
                }
            }
        }
    }
}
