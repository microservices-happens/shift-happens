package dk.ek.shift_happens.identity.infrastructure.messaging;

import dk.ek.shift_happens.identity.application.EmployeeProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Consumes workforce.employee.created/updated/deleted.v1 from the identity.accounts queue. */
@Component
public class EmployeeEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmployeeEventListener.class);

    private final EmployeeProjectionService projection;

    EmployeeEventListener(EmployeeProjectionService projection) {
        this.projection = projection;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void onEmployeeEvent(EventEnvelope envelope) {
        log.info("Received {} for employee {} (v{}), correlation {}",
                envelope.eventType(), envelope.aggregateId(), envelope.aggregateVersion(),
                envelope.correlationId());
        projection.apply(envelope);
    }
}
