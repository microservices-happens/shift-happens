package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Publishes unpublished outbox rows to the topic exchange. At-least-once: a crash after publish and
 * before the "published" update can resend an event, which is why consumers de-duplicate on eventId.
 *
 * <p>The payload is already serialized JSON, so it is sent as a raw message and not converted again.
 * The routing key is the eventType (contracts/README.md).
 */
@Component
@Profile("command")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final TextMapGetter<OutboxEventEntity> TRACE_HEADERS = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(OutboxEventEntity event) {
            return List.of("traceparent", "tracestate");
        }

        @Override
        public String get(OutboxEventEntity event, String key) {
            if (event == null) return null;
            return switch (key) {
                case "traceparent" -> event.getTraceparent();
                case "tracestate" -> event.getTracestate();
                default -> null;
            };
        }
    };

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
            // The request and this scheduled relay run in different threads and transactions.
            Context context = W3CTraceContextPropagator.getInstance()
                    .extract(Context.current(), event, TRACE_HEADERS);
            try (Scope ignored = context.makeCurrent()) {
                rabbitTemplate.send(exchange, event.getEventType(), messageFor(event));
            }
            event.markPublished(Instant.now());
            repository.save(event);
            log.info("Published {} for aggregate {}", event.getEventType(), event.getAggregateId());
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
