
package dk.ek.shift_happens.notification.infrastructure.messaging;

import dk.ek.shift_happens.notification.infrastructure.persistence.OutboxEventEntity;
import dk.ek.shift_happens.notification.infrastructure.persistence.OutboxEventRepository;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final String EXCHANGE = "shift-happens.events";

    private final OutboxEventRepository outboxEventRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            RabbitTemplate rabbitTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {

        List<OutboxEventEntity> events =
                outboxEventRepository.findUnpublishedEvents();

        for (OutboxEventEntity event : events) {
            try {
                CorrelationData correlationData =
                        new CorrelationData(event.getEventId().toString());

                rabbitTemplate.convertAndSend(
                        EXCHANGE,
                        event.getEventType(),
                        event.getPayload(),
                        correlationData
                );

                CorrelationData.Confirm confirm =
                        correlationData.getFuture().get(10, TimeUnit.SECONDS);

                if (!confirm.isAck()) {
                    throw new IllegalStateException(
                            "RabbitMQ rejected message: " + confirm.getReason()
                    );
                }

                if (correlationData.getReturned() != null) {
                    throw new IllegalStateException(
                            "Message could not be routed to a queue"
                    );
                }

                event.markPublished();
                outboxEventRepository.save(event);

                System.out.println(
                        "Successfully published event: " + event.getEventId()
                );

            } catch (Exception e) {
                System.err.println(
                        "Failed to publish event " + event.getEventId()
                        + ": " + e.getMessage()
                );
            }
        }
    }
}
