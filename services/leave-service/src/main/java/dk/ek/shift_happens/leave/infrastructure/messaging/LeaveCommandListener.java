package dk.ek.shift_happens.leave.infrastructure.messaging;

import dk.ek.shift_happens.leave.application.EmployeeReplicaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The single consumer of the leave.command queue.
 *
 * <p>One queue has one listener that dispatches on eventType: two listeners on the same queue would be
 * competing consumers, and a message could land on the one that does not handle it. Today only
 * workforce.employee.{created,updated,deleted}.v1 (local replica) is bound, by exact routing key; the saga
 * replies join with the saga ticket (#47).
 */
@Component
@Profile("command")
public class LeaveCommandListener {

    private static final Logger log = LoggerFactory.getLogger(LeaveCommandListener.class);

    private final EmployeeReplicaService replica;

    LeaveCommandListener(EmployeeReplicaService replica) {
        this.replica = replica;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void onEvent(EventEnvelope envelope) {
        log.info("Received {} for aggregate {} (v{}), correlation {}", envelope.eventType(),
                envelope.aggregateId(), envelope.aggregateVersion(), envelope.correlationId());

        switch (envelope.eventType()) {
            case LeaveCommandTopology.EMPLOYEE_CREATED, LeaveCommandTopology.EMPLOYEE_UPDATED ->
                    replica.applySnapshot(envelope);
            case LeaveCommandTopology.EMPLOYEE_DELETED -> replica.applyTombstone(envelope);
            default -> log.warn("Ignoring unexpected event type {}", envelope.eventType());
        }
    }
}
