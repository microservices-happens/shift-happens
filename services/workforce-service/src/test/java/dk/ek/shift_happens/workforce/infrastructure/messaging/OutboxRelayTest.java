package dk.ek.shift_happens.workforce.infrastructure.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The relay is the only path from the outbox to RabbitMQ, so its failure behaviour is what keeps the
 * at-least-once contract: a failed publish stays unpublished with an increased attempt count, and the
 * next scheduled tick retries it. Nothing is lost; consumers de-duplicate on eventId.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final String EXCHANGE = "shift-happens.events";
    private static final String EVENT_TYPE = "workforce.employee.created.v1";

    @Mock
    private OutboxEventJpaRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, rabbitTemplate, EXCHANGE, 2000);
    }

    @Test
    void publishesPendingRowsAsRawJsonAndMarksThemPublished() {
        OutboxEventEntity event = newEvent("{\"employeeId\":42,\"jobRoleIds\":[1]}");
        when(repository.findTop50ByPublishedAtIsNullOrderByOccurredAtAsc()).thenReturn(List.of(event));

        relay.publishPending();

        ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(EXCHANGE), eq(EVENT_TYPE), sent.capture());
        Message message = sent.getValue();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(event.getPayload());
        assertThat(message.getMessageProperties().getContentType())
                .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.getEventId().toString());
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        verify(repository).save(event);
    }

    @Test
    void aBrokerFailureLeavesTheRowUnpublishedForTheNextTick() {
        OutboxEventEntity event = newEvent("{\"employeeId\":42}");
        when(repository.findTop50ByPublishedAtIsNullOrderByOccurredAtAsc()).thenReturn(List.of(event));
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).send(eq(EXCHANGE), eq(EVENT_TYPE), any(Message.class));

        relay.publishPending();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        verify(repository).save(event);

        // The broker is back: the same row is published and marked on the next tick.
        org.mockito.Mockito.reset(rabbitTemplate);
        relay.publishPending();
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void aDatabaseFailureDoesNotEscapeTheScheduledTick() {
        when(repository.findTop50ByPublishedAtIsNullOrderByOccurredAtAsc())
                .thenThrow(new IllegalStateException("database down"));

        assertThatCode(relay::publishPending).doesNotThrowAnyException();
        verifyNoInteractions(rabbitTemplate);
    }

    private static OutboxEventEntity newEvent(String payload) {
        return new OutboxEventEntity(UUID.randomUUID(), EVENT_TYPE, 42L, payload, Instant.now());
    }
}
