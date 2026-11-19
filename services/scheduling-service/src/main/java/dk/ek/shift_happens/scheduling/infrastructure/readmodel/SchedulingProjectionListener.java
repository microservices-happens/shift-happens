package dk.ek.shift_happens.scheduling.infrastructure.readmodel;

import dk.ek.shift_happens.scheduling.application.SchedulingProjectionService;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The single consumer of the scheduling.projection queue.
 *
 * <p>One listener dispatches nothing: everything the read model needs goes through the projection
 * service, which decides per eventType. That keeps one queue and one consumer group, as
 * contracts/README.md requires.
 */
@Component
@Profile("query")
class SchedulingProjectionListener {

    private static final Logger log = LoggerFactory.getLogger(SchedulingProjectionListener.class);

    private final SchedulingProjectionService projection;

    SchedulingProjectionListener(SchedulingProjectionService projection) {
        this.projection = projection;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void onEvent(EventEnvelope envelope) {
        log.info("Projecting {} for aggregate {} (v{})", envelope.eventType(), envelope.aggregateId(),
                envelope.aggregateVersion());
        projection.apply(envelope);
    }
}
