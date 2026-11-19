package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.domain.BalanceKey;
import dk.ek.shift_happens.leave.domain.ReadModelReader;
import dk.ek.shift_happens.leave.infrastructure.messaging.ProjectionEnvelope;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The leave.projection consumer against real MongoDB (standalone, no transactions) and RabbitMQ: every bound event is
 * applied, and redelivery, reordering, tombstones, unknown events and poison messages are handled without a
 * transaction anywhere.
 */
@ExtendWith(OutputCaptureExtension.class)
class LeaveProjectionIT extends AbstractQueryIT {

    @Autowired
    private ReadModelReader reader;

    // ---- every bound event type ----

    @Test
    void everyBoundEventTypeIsApplied() {
        publish(Events.TYPE_CREATED, 5, 1, Events.type(5, "Vacation", "Paid", true, true));
        publish(Events.TYPE_UPDATED, 6, 2, Events.type(6, "Sick", null, false, false));
        publish(Events.TYPE_DELETED, 7, 2, Events.tombstone("leaveType", 7));
        publish(Events.EMPLOYEE_CREATED, 10, 1, Events.employee(10, "Grace", "Hopper"));
        publish(Events.EMPLOYEE_UPDATED, 11, 2, Events.employee(11, "Ada", "Lovelace"));
        publish(Events.EMPLOYEE_DELETED, 12, 3, Events.tombstone("employee", 12));
        publish(Events.SUBMITTED, 20, 1, Events.request(20, 10, 5, "Vacation", "2027-05-03", "2027-05-04", "PENDING"));
        Map<String, Object> started = Events.decided(
                Events.request(21, 10, 5, "Vacation", "2027-06-01", "2027-06-02", "APPROVAL_PENDING"), 9L, 99,
                "APPROVED", "ok");
        publish(Events.APPROVAL_STARTED, 21, 2, started);
        publish(Events.APPROVED, 22, 3, Events.decided(
                Events.request(22, 10, 5, "Vacation", "2027-07-01", "2027-07-02", "APPROVED"), 10L, 99, "APPROVED", null));
        publish(Events.REJECTED, 23, 2, Events.decided(
                Events.request(23, 10, 5, "Vacation", "2027-08-01", "2027-08-02", "REJECTED"), 11L, 99, "REJECTED", "no"));
        publish(Events.CANCELLED, 24, 2, Events.request(24, 10, 5, "Vacation", "2027-09-01", "2027-09-02", "CANCELLED"));
        ProjectionEnvelope last = publish(Events.LEDGER, 100, 1,
                Events.ledger(100, 10, 5, "-2.50", "7.50", "RESERVATION"));
        awaitProcessed(last);
        drain();

        assertThat(doc("leave_types", 5).getString("leaveTypeName")).isEqualTo("Vacation");
        assertThat(doc("leave_types", 6).getString("leaveTypeName")).as("an update before any create").isEqualTo("Sick");
        assertThat(doc("leave_types", 7).getBoolean("deleted")).isTrue();
        assertThat(doc("employees", 10).getString("lastName")).isEqualTo("Hopper");
        assertThat(doc("employees", 11).getString("lastName")).isEqualTo("Lovelace");
        assertThat(doc("employees", 12).getBoolean("deleted")).isTrue();
        assertThat(doc("leave_requests", 20).getString("requestStatus")).isEqualTo("PENDING");
        assertThat(doc("leave_requests", 21).getString("requestStatus")).isEqualTo("APPROVAL_PENDING");
        assertThat(doc("leave_requests", 21).get("decision", Document.class).get("leaveApprovalId")).isEqualTo(9L);
        assertThat(doc("leave_requests", 22).getString("requestStatus")).isEqualTo("APPROVED");
        assertThat(doc("leave_requests", 23).getString("requestStatus")).isEqualTo("REJECTED");
        assertThat(doc("leave_requests", 24).getString("requestStatus")).as("cancelled.v1 is projected too")
                .isEqualTo("CANCELLED");
        assertThat(doc("leave_requests", 24).get("version")).isEqualTo(2L);
        Document entry = doc("ledger_entries", 100);
        assertThat(((Decimal128) entry.get("changeAmountDays")).bigDecimalValue()).isEqualByComparingTo("-2.5");
        assertThat(((Decimal128) entry.get("balanceAfterDays")).bigDecimalValue()).isEqualByComparingTo("7.5");
    }

    @Test
    void anEmployeeDocumentKeepsTheNameOnly() {
        awaitProcessed(publish(Events.EMPLOYEE_CREATED, 10, 1, Events.employee(10, "Grace", "Hopper")));

        assertThat(doc("employees", 10).keySet()).containsExactlyInAnyOrder("_id", "version", "firstName",
                "lastName", "deleted");
    }

    // ---- idempotency without transactions ----

    @Test
    void aRedeliveredEventIdIsNotAppliedAgainEvenWithADifferentPayloadAndANewerVersion() {
        UUID eventId = UUID.randomUUID();
        ProjectionEnvelope first = envelope(eventId, Events.EMPLOYEE_CREATED, 30, 1,
                Events.employee(30, "Ada", "Original"));
        publish(first);
        awaitProcessed(first);

        // Same eventId, a newer version and another name: only the processed_events check can stop this one.
        publish(envelope(eventId, Events.EMPLOYEE_UPDATED, 30, 2, Events.employee(30, "Ada", "Changed")));
        drain();

        assertThat(doc("employees", 30).getString("lastName")).isEqualTo("Original");
        assertThat(doc("employees", 30).get("version")).isEqualTo(1L);
    }

    @Test
    void aDuplicateLedgerEntryUnderANewEventIdIsTheSameEntryAndCountsOnce() {
        publishAndAwait(Events.LEDGER, 101, 1, Events.ledger(101, 10, 5, "4.00", "4.00", "ACCRUAL"));
        publishAndAwait(Events.LEDGER, 101, 1, Events.ledger(101, 10, 5, "99.00", "99.00", "ACCRUAL"));
        publishAndAwait(Events.LEDGER, 102, 1, Events.ledger(102, 10, 5, "-1.00", "3.00", "RESERVATION"));

        assertThat(mongo.count(new Query(), "ledger_entries")).isEqualTo(2);
        assertThat(balanceOf(10, 5)).isEqualByComparingTo("3.00");
    }

    @Test
    void theBalanceIsTheSumOfTheEntriesWhateverTheArrivalOrderAndNotTheLastBalanceAfter() {
        // Arrival order 3, 1, 2: the last arrived entry says balanceAfterDays=2.00, the true sum is 5.50.
        publishAndAwait(Events.LEDGER, 203, 1, Events.ledger(203, 10, 5, "-1.00", "2.00", "RESERVATION"));
        publishAndAwait(Events.LEDGER, 201, 1, Events.ledger(201, 10, 5, "4.00", "4.00", "ACCRUAL"));
        publishAndAwait(Events.LEDGER, 202, 1, Events.ledger(202, 10, 5, "2.50", "6.50", "ACCRUAL"));

        assertThat(balanceOf(10, 5)).isEqualByComparingTo("5.50");
    }

    // ---- version guard and tombstones ----

    @Test
    void aLowerVersionAfterAHigherOneIsIgnoredForEmployeesRequestsAndLeaveTypes() {
        publishAndAwait(Events.EMPLOYEE_UPDATED, 31, 5, Events.employee(31, "Ada", "Newest"));
        publishAndAwait(Events.EMPLOYEE_UPDATED, 31, 2, Events.employee(31, "Ada", "Older"));
        publishAndAwait(Events.APPROVED, 40, 3, Events.request(40, 31, 5, "Vacation", "2027-05-03", "2027-05-04", "APPROVED"));
        publishAndAwait(Events.SUBMITTED, 40, 1, Events.request(40, 31, 5, "Vacation", "2027-05-03", "2027-05-04", "PENDING"));
        publishAndAwait(Events.TYPE_UPDATED, 5, 4, Events.type(5, "Newest"));
        publishAndAwait(Events.TYPE_CREATED, 5, 1, Events.type(5, "Older"));

        assertThat(doc("employees", 31).getString("lastName")).isEqualTo("Newest");
        assertThat(doc("leave_requests", 40).getString("requestStatus")).isEqualTo("APPROVED");
        assertThat(doc("leave_requests", 40).get("version")).isEqualTo(3L);
        assertThat(doc("leave_types", 5).getString("leaveTypeName")).isEqualTo("Newest");
    }

    @Test
    void aTombstoneArrivingBeforeTheCreateWinsOverEverySnapshotAfterIt() {
        publishAndAwait(Events.EMPLOYEE_DELETED, 41, 2, Events.tombstone("employee", 41));
        publishAndAwait(Events.EMPLOYEE_CREATED, 41, 1, Events.employee(41, "Late", "Create"));
        publishAndAwait(Events.EMPLOYEE_UPDATED, 41, 7, Events.employee(41, "Late", "Update"));
        publishAndAwait(Events.TYPE_DELETED, 8, 3, Events.tombstone("leaveType", 8));
        publishAndAwait(Events.TYPE_CREATED, 8, 1, Events.type(8, "Late create"));
        publishAndAwait(Events.TYPE_UPDATED, 8, 9, Events.type(8, "Late update"));

        assertThat(doc("employees", 41).getBoolean("deleted")).isTrue();
        assertThat(doc("employees", 41).getString("lastName")).isNull();
        assertThat(doc("leave_types", 8).getBoolean("deleted")).isTrue();
        assertThat(http.getForEntity("/v1/leavetypes/8", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aTombstoneAfterTheSnapshotsKeepsTheDocumentAndTheHighestVersion() {
        publishAndAwait(Events.EMPLOYEE_CREATED, 42, 5, Events.employee(42, "Soon", "Gone"));
        publishAndAwait(Events.EMPLOYEE_DELETED, 42, 3, Events.tombstone("employee", 42));

        Document employee = doc("employees", 42);
        assertThat(employee.getBoolean("deleted")).as("an older tombstone still deletes").isTrue();
        assertThat(employee.get("version")).as("the version never moves down").isEqualTo(5L);
        assertThat(employee.getString("firstName")).as("the document is kept").isEqualTo("Soon");
    }

    @Test
    void aDeletedLeaveTypeIsHiddenFromTheTypeEndpointsAndItsRowsKeepItsLastName() {
        publishAndAwait(Events.TYPE_CREATED, 50, 1, Events.type(50, "Vacation"));
        publishAndAwait(Events.EMPLOYEE_CREATED, 10, 1, Events.employee(10, "Grace", "Hopper"));
        publishAndAwait(Events.SUBMITTED, 60, 1, Events.request(60, 10, 50, "Vacation", "2027-05-03", "2027-05-04", "PENDING"));
        publishAndAwait(Events.TYPE_UPDATED, 50, 2, Events.type(50, "Holiday"));
        assertThat(textsOf(getJson("/v1/leavetypes"), "leaveTypeName")).containsExactly("Holiday");
        assertThat(textsOf(getJson("/v1/views/employee-leave-overview"), "leaveTypeName")).containsExactly("Holiday");

        publishAndAwait(Events.TYPE_DELETED, 50, 3, Events.tombstone("leaveType", 50));

        assertThat(getJson("/v1/leavetypes")).isEmpty();
        assertThat(http.getForEntity("/v1/leavetypes/50", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(textsOf(getJson("/v1/views/employee-leave-overview/leave-type/Holiday"), "leaveRequestId"))
                .as("the command database keeps the deleted type's row and last name").containsExactly("60");
        assertThat(getJson("/v1/views/employee-leave-overview/leave-type/Vacation")).isEmpty();
        assertThat(textsOf(getJson("/v1/views/employee-leave-overview"), "leaveTypeName"))
                .as("the row keeps the type's last name, like the command database").containsExactly("Holiday");
        assertThat(getJson("/v1/leaverequests/60").path("leaveRequestId").asLong()).isEqualTo(60);
    }

    @Test
    void aLeaveTypesLastNameWinsInAnyOrderEvenAfterItsTombstone() {
        publishAndAwait(Events.EMPLOYEE_CREATED, 10, 1, Events.employee(10, "Grace", "Hopper"));
        publishAndAwait(Events.SUBMITTED, 61, 1, Events.request(61, 10, 51, "First", "2027-05-03", "2027-05-04", "PENDING"));
        publishAndAwait(Events.TYPE_DELETED, 51, 4, Events.tombstone("leaveType", 51));
        publishAndAwait(Events.TYPE_UPDATED, 51, 3, Events.type(51, "Final"));
        publishAndAwait(Events.TYPE_CREATED, 51, 1, Events.type(51, "First"));
        publishAndAwait(Events.TYPE_UPDATED, 51, 2, Events.type(51, "Second"));

        assertThat(textsOf(getJson("/v1/views/employee-leave-overview"), "leaveTypeName")).containsExactly("Final");
        assertThat(textsOf(getJson("/v1/views/employee-leave-overview/leave-type/final"), "leaveRequestId"))
                .containsExactly("61");
        assertThat(http.getForEntity("/v1/leavetypes/51", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        Document type = doc("leave_types", 51);
        assertThat(type.getBoolean("deleted")).isTrue();
        assertThat(type.get("version")).isEqualTo(3L);
        assertThat(type.get("deletedVersion")).isEqualTo(4L);
    }

    @Test
    void anEqualVersionUnderANewEventIdIsIgnored() {
        publishAndAwait(Events.EMPLOYEE_UPDATED, 32, 2, Events.employee(32, "Ada", "First"));
        publishAndAwait(Events.EMPLOYEE_UPDATED, 32, 2, Events.employee(32, "Ada", "Second"));
        publishAndAwait(Events.SUBMITTED, 43, 2, Events.request(43, 32, 9, "Vacation", "2027-05-03", "2027-05-04", "PENDING"));
        publishAndAwait(Events.CANCELLED, 43, 2, Events.request(43, 32, 9, "Vacation", "2027-05-03", "2027-05-04", "CANCELLED"));
        publishAndAwait(Events.TYPE_UPDATED, 9, 2, Events.type(9, "First"));
        publishAndAwait(Events.TYPE_UPDATED, 9, 2, Events.type(9, "Second"));

        assertThat(doc("employees", 32).getString("lastName")).isEqualTo("First");
        assertThat(doc("leave_requests", 43).getString("requestStatus")).isEqualTo("PENDING");
        assertThat(doc("leave_types", 9).getString("leaveTypeName")).isEqualTo("First");
    }

    // ---- unknown, poison, bindings ----

    @Test
    void anEventTypeNobodyHandlesIsRecordedAndIgnored() {
        ProjectionEnvelope unknown = envelope(UUID.randomUUID(), "leave.request.expired.v1", 70, 1,
                Events.request(70, 10, 5, "Vacation", "2027-05-03", "2027-05-04", "PENDING"));

        publishToQueue(unknown);
        awaitProcessed(unknown);

        assertThat(mongo.count(new Query(), "leave_requests")).isZero();
    }

    @Test
    void aPoisonMessageIsTriedThreeTimesThenDeadLetteredWithTheQueueNameAsRoutingKeyAndNothingElseIsDeadLettered(
            CapturedOutput output) throws Exception {
        UUID poisonId = UUID.randomUUID();
        long startedAt = System.nanoTime();
        // No leaveLedgerId: it can never be applied.
        publish(envelope(poisonId, Events.LEDGER, 777, 1, Map.of("employeeId", 10, "leaveTypeId", 5,
                "changeAmountDays", new BigDecimal("1"))));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(messagesIn(QUEUE + ".dlq")).isEqualTo(1));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt).toMillis())
                .as("three attempts, 1 s then 2 s apart").isGreaterThanOrEqualTo(2500);
        for (JsonNode queue : managementGet("/api/queues/%2F")) {
            String name = queue.path("name").asText();
            if (!name.equals(QUEUE + ".dlq")) {
                assertThat(messagesIn(name)).as("no messages in " + name).isZero();
            }
        }
        Message dead = awaitDeadLetter(poisonId.toString());

        assertThat(dead.getMessageProperties().getReceivedRoutingKey())
                .as("dead letters leave with the queue name as routing key").isEqualTo(QUEUE);
        assertThat(count(output.getAll(), "Received " + Events.LEDGER + " for aggregate 777 "))
                .as("three delivery attempts, no requeue loop").isEqualTo(3);
        assertThat(mongo.exists(Query.query(Criteria.where("_id").is(poisonId.toString())), "processed_events"))
                .isFalse();
    }

    @Test
    void aMalformedMessageIsDeadLetteredToo() {
        String marker = UUID.randomUUID().toString();
        Message malformed = org.springframework.amqp.core.MessageBuilder
                .withBody(("{\"eventId\": \"" + marker + "\", broken").getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build();
        rabbitTemplate.send("shift-happens.events", Events.LEDGER, malformed);

        assertThat(awaitDeadLetter(marker).getMessageProperties().getReceivedRoutingKey()).isEqualTo(QUEUE);
    }

    @Test
    void theQueueIsBoundToExactlyTheTwelveEventTypesAndTheDeadLetterQueueToItsOwnKey() throws Exception {
        assertThat(routingKeys(QUEUE, "shift-happens.events")).containsExactlyInAnyOrderElementsOf(Events.BOUND);
        assertThat(routingKeys(QUEUE + ".dlq", "shift-happens.dlx")).containsExactly(QUEUE);
        JsonNode queue = managementGet("/api/queues/%2F/" + QUEUE);
        assertThat(queue.path("durable").asBoolean()).isTrue();
        assertThat(queue.path("arguments").path("x-dead-letter-exchange").asText()).isEqualTo("shift-happens.dlx");
        assertThat(queue.path("arguments").path("x-dead-letter-routing-key").asText()).isEqualTo(QUEUE);
    }

    @Test
    void eventTypesThatAreNotBoundNeverReachTheQueue(CapturedOutput output) {
        for (String unbound : List.of("workforce.employee.renamed.v1", "leave.request.expired.v1",
                "leave.type.renamed.v1", "leave.ledger.corrected.v1", "scheduling.leave-release.completed.v1")) {
            publish(unbound, 1, 1, Map.of("employeeId", 1));
        }
        drain();

        assertThat(output.getAll()).doesNotContain("Received workforce.employee.renamed")
                .doesNotContain("Received leave.request.expired").doesNotContain("Received leave.type.renamed")
                .doesNotContain("Received leave.ledger.corrected")
                .doesNotContain("Received scheduling.leave-release");
        assertThat(mongo.count(new Query(), "processed_events")).as("only the sentinel").isEqualTo(1);
    }

    @Test
    void theDeadLetterQueueIgnoresForeignDeadLetters() {
        String marker = UUID.randomUUID().toString();
        String sentinel = UUID.randomUUID().toString();
        rabbitTemplate.execute(channel -> {
            channel.basicPublish("shift-happens.dlx", "scheduling.command", null,
                    marker.getBytes(StandardCharsets.UTF_8));
            channel.basicPublish("shift-happens.dlx", QUEUE, null, sentinel.getBytes(StandardCharsets.UTF_8));
            return null;
        });

        List<String> arrived = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            Message message;
            while ((message = rabbitTemplate.receive(QUEUE + ".dlq", 300)) != null) {
                arrived.add(new String(message.getBody(), StandardCharsets.UTF_8));
            }
            return arrived.stream().anyMatch(body -> body.contains(sentinel));
        });

        assertThat(arrived).as("a dead letter of another queue must not land in " + QUEUE + ".dlq")
                .noneMatch(body -> body.contains(marker));
    }

    // ---- helpers ----

    private Document doc(String collection, long id) {
        Document document = mongo.findById(id, Document.class, collection);
        assertThat(document).as("%s/%d", collection, id).isNotNull();
        return document;
    }

    private BigDecimal balanceOf(long employeeId, long leaveTypeId) {
        return reader.balances(Set.of(employeeId)).get(new BalanceKey(employeeId, leaveTypeId));
    }

    /** Exact and immediate (the management API's counters lag by a few seconds). */
    private int messagesIn(String queue) {
        return rabbitTemplate.execute(channel -> (int) channel.messageCount(queue));
    }

    private List<String> routingKeys(String queue, String exchange) throws Exception {
        List<String> keys = new ArrayList<>();
        for (JsonNode binding : managementGet("/api/queues/%2F/" + queue + "/bindings")) {
            if (binding.path("source").asText().equals(exchange)) {
                keys.add(binding.path("routing_key").asText());
            }
        }
        return keys;
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
