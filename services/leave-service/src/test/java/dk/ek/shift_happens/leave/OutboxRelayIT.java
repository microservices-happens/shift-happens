package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.leave.application.DomainEvents;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void anEventAppendedInATransactionIsRelayedWithTheContractEnvelopeAndMarkedPublished() throws Exception {
        String queue = bindTestQueue("it.leave.relay." + UUID.randomUUID(), EVENT_TYPE);
        String correlationId = UUID.randomUUID().toString();
        long aggregateId = 4711L;

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                domainEvents.append(EVENT_TYPE, aggregateId, 1, correlationId, Map.of("leaveRequestId", aggregateId)));

        Message message = receive(queue);
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .as("routing key == eventType, so consumers can bind on it").isEqualTo(EVENT_TYPE);
        JsonNode envelope = objectMapper.readTree(message.getBody());
        // Field set of contracts/events/envelope.schema.json.
        assertThat(envelope.path("eventId").asText()).isNotBlank();
        assertThat(UUID.fromString(envelope.path("eventId").asText())).isNotNull();
        assertThat(envelope.path("eventType").asText()).isEqualTo(EVENT_TYPE);
        assertThat(envelope.path("producer").asText()).isEqualTo("leave-service");
        assertThat(envelope.path("aggregateId").asLong()).isEqualTo(aggregateId);
        assertThat(envelope.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(envelope.path("correlationId").asText())
                .as("the correlation id of the request must survive into the event").isEqualTo(correlationId);
        assertThat(envelope.path("occurredAt").asText()).isNotBlank();
        assertThat(envelope.path("data").path("leaveRequestId").asLong()).isEqualTo(aggregateId);

        String eventId = envelope.path("eventId").asText();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_id = ?::uuid AND published_at IS NOT NULL",
                Integer.class, eventId)).as("the relay must mark the row published, or it resends forever")
                .isEqualTo(1));
    }

    @Test
    void aMissingCorrelationIdIsReplacedSoEveryEventIsTraceable() throws Exception {
        String queue = bindTestQueue("it.leave.relay." + UUID.randomUUID(), EVENT_TYPE);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                domainEvents.append(EVENT_TYPE, 1L, 1, null, Map.of()));

        JsonNode envelope = objectMapper.readTree(receive(queue).getBody());
        assertThat(envelope.path("correlationId").asText()).isNotBlank();
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        String queue = bindTestQueue("it.leave.relay." + UUID.randomUUID(), EVENT_TYPE);
        long aggregateId = 9_000_000L + (System.nanoTime() % 1000);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            domainEvents.append(EVENT_TYPE, aggregateId, 1, "rollback", Map.of());
            status.setRollbackOnly();
        });

        // Wait several relay ticks (200 ms each) and assert nothing was sent or stored.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(rabbitTemplate.receive(queue)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?",
                    Integer.class, aggregateId)).isZero();
        });
    }

    private Message receive(String queue) {
        AtomicReference<Message> received = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            received.set(rabbitTemplate.receive(queue));
            assertThat(received.get()).as("a message on %s", queue).isNotNull();
        });
        return received.get();
    }
}
