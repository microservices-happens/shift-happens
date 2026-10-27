package dk.ek.shift_happens.workforce.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Publishes unpublished outbox rows to the topic exchange. At-least-once: a crash after publish and
 * before the "published" update can resend an event, which is why consumers de-duplicate on eventId.
 *
 * <p>The payload is already serialized JSON, so it is sent as a raw message and not converted again.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventJpaRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final long relayDelay;

    OutboxRelay(OutboxEventJpaRepository repository, RabbitTemplate rabbitTemplate,
                @Value("${app.events.exchange}") String exchange,
                @Value("${app.outbox.relay-delay:2000}") long relayDelay) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.relayDelay = relayDelay;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-delay:2000}")
    public void publishPending() {
        try {
            for (OutboxEventEntity event : repository.findTop50ByPublishedAtIsNullOrderByOccurredAtAsc()) {
                publish(event);
            }
        } catch (RuntimeException e) {
            // A broker or database blip is retried on the next tick; do not spam a stack trace.
            log.warn("Outbox relay run failed, retrying in {} ms: {}", relayDelay, e.toString());
        }
    }

    private void publish(OutboxEventEntity event) {
        try {
            rabbitTemplate.send(exchange, event.getEventType(), messageFor(event));
            event.markPublished(Instant.now());
            repository.save(event);
        } catch (RuntimeException e) {
            event.recordFailure();
            repository.save(event);
            log.warn("Could not publish outbox event {} (attempt {}): {}",
                    event.getEventId(), event.getAttempts(), e.toString());
        }
    }

    private static Message messageFor(OutboxEventEntity event) {
        return MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(event.getEventId().toString())
                .build();
    }
}
