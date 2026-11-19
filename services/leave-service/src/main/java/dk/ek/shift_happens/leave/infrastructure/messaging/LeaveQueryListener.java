package dk.ek.shift_happens.leave.infrastructure.messaging;

import dk.ek.shift_happens.leave.application.ProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The single consumer of the leave.projection queue. One queue has one listener that dispatches on eventType (in
 * {@link ProjectionService}): two listeners on one queue would be competing consumers.
 */
@Component
@Profile("query")
public class LeaveQueryListener {

    private static final Logger log = LoggerFactory.getLogger(LeaveQueryListener.class);

    private final ProjectionService projection;

    LeaveQueryListener(ProjectionService projection) {
        this.projection = projection;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void onEvent(ProjectionEnvelope envelope) {
        log.info("Received {} for aggregate {} (v{}), correlation {}", envelope.eventType(),
                envelope.aggregateId(), envelope.aggregateVersion(), envelope.correlationId());
        projection.handle(envelope);
    }
}
