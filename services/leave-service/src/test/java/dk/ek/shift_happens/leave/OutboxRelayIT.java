package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.application.DomainEvents;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox is what guarantees that a committed state change is never left without
 * its event (and that no event is sent for a rolled-back change). Real Postgres and RabbitMQ.
 */
class OutboxRelayIT extends AbstractCommandIT {

    private static final String EVENT_TYPE = "leave.request.submitted.v1";

    @Autowired
    private DomainEvents domainEvents;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void anEventAppendedInATransactionIsRelayedWithTheContractEnvelopeAndMarkedPublished() throws Exception {
        String queue = bindTestQueue("it.leave.relay." + UUID.randomUUID(), EVENT_TYPE);
        String correlationId = UUID.randomUUID().toString();
        long aggregateId = syntheticAggregateId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                domainEvents.append(EVENT_TYPE, aggregateId, 1, correlationId, Map.of("leaveRequestId", aggregateId)));

        Message message = receive(queue, envelope -> envelope.path("aggregateId").asLong() == aggregateId);
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .as("routing key == eventType, so consumers can bind on it").isEqualTo(EVENT_TYPE);
        JsonNode envelope = objectMapper.readTree(message.getBody());
        // Field set of contracts/events/envelope.schema.json.
        UUID eventId = uuidOf(envelope.path("eventId"));
        assertThat(envelope.path("eventType").asText()).isEqualTo(EVENT_TYPE);
        assertThat(envelope.path("producer").asText()).isEqualTo("leave-service");
        assertThat(envelope.path("aggregateId").asLong()).isEqualTo(aggregateId);
        assertThat(envelope.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(envelope.path("correlationId").asText())
                .as("the correlation id of the request must survive into the event").isEqualTo(correlationId);
        assertThat(instantOf(envelope.path("occurredAt"))).isNotNull();
        assertThat(envelope.path("data").path("leaveRequestId").asLong()).isEqualTo(aggregateId);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_id = ? AND published_at IS NOT NULL",
                Integer.class, eventId)).as("the relay must mark the row published, or it resends forever")
                .isEqualTo(1));
    }

    @Test
    void aMissingCorrelationIdIsReplacedSoEveryEventIsTraceable() throws Exception {
        String queue = bindTestQueue("it.leave.relay." + UUID.randomUUID(), EVENT_TYPE);
        long aggregateId = syntheticAggregateId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                domainEvents.append(EVENT_TYPE, aggregateId, 1, null, Map.of()));

        JsonNode envelope = objectMapper.readTree(
                receive(queue, node -> node.path("aggregateId").asLong() == aggregateId).getBody());
        assertThat(uuidOf(envelope.path("correlationId"))).isNotNull();
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        String queue = bindTestQueue("it.leave.relay." + UUID.randomUUID(), EVENT_TYPE);
        long aggregateId = syntheticAggregateId();
        String correlationId = "rollback-" + UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            domainEvents.append(EVENT_TYPE, aggregateId, 1, correlationId, Map.of());
            status.setRollbackOnly();
        });

        // Wait several relay ticks (200 ms each); only messages of this test count, other tests share the exchange.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Message message;
            while ((message = rabbitTemplate.receive(queue)) != null) {
                String body = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(body).doesNotContain(correlationId);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?",
                    Integer.class, aggregateId)).isZero();
        });
    }

    /** Close to Long.MAX_VALUE: a BIGSERIAL id of a real leave request can never collide with it. */
    private static long syntheticAggregateId() {
        return Long.MAX_VALUE - ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }

    /** Drains the queue until a message matching the predicate arrives; other tests' messages are discarded. */
    private Message receive(String queue, Predicate<JsonNode> matches) {
        AtomicReference<Message> received = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message message;
            while ((message = rabbitTemplate.receive(queue)) != null) {
                if (matches.test(objectMapper.readTree(message.getBody()))) {
                    received.set(message);
                    return;
                }
            }
            assertThat(received.get()).as("a matching message on %s", queue).isNotNull();
        });
        return received.get();
    }
}
