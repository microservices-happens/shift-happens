package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import dk.ek.shift_happens.scheduling.application.EmployeeReplicaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Consumes workforce.employee.created/updated/deleted.v1 from the scheduling.command queue. */
@Component
@Profile("command")
public class EmployeeEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmployeeEventListener.class);

    private final EmployeeReplicaService replica;

    EmployeeEventListener(EmployeeReplicaService replica) {
        this.replica = replica;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void onEmployeeEvent(EventEnvelope envelope) {
        log.info("Received {} for employee {} (v{}), correlation {}", envelope.eventType(),
                envelope.aggregateId(), envelope.aggregateVersion(), envelope.correlationId());
        replica.apply(envelope);
    }
}
