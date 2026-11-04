package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import dk.ek.shift_happens.scheduling.application.EmployeeReplicaService;
import dk.ek.shift_happens.scheduling.application.LeaveReleaseService;
import dk.ek.shift_happens.scheduling.application.LeaveWindowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The single consumer of the scheduling.command queue.
 *
 * <p>One queue has one listener that dispatches on eventType: two listeners on the same queue would be
 * competing consumers, and a message could land on the one that does not handle it. Routing keys:
 * workforce.employee.* (replica, P2.8), leave.request.approval-started.v1 (saga, P3.1) and
 * leave.request.approved|.cancelled.v1 (leave windows for the understaffed candidates, P2.12).
 */
@Component
@Profile("command")
public class SchedulingCommandListener {

    private static final Logger log = LoggerFactory.getLogger(SchedulingCommandListener.class);

    private static final String LEAVE_APPROVAL_STARTED = "leave.request.approval-started.v1";
    private static final String LEAVE_APPROVED = "leave.request.approved.v1";
    private static final String LEAVE_CANCELLED = "leave.request.cancelled.v1";

    private final EmployeeReplicaService replica;
    private final LeaveReleaseService leaveReleases;
    private final LeaveWindowService leaveWindows;

    SchedulingCommandListener(EmployeeReplicaService replica, LeaveReleaseService leaveReleases,
                              LeaveWindowService leaveWindows) {
        this.replica = replica;
        this.leaveReleases = leaveReleases;
        this.leaveWindows = leaveWindows;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void onEvent(EventEnvelope envelope) {
        log.info("Received {} for aggregate {} (v{}), correlation {}", envelope.eventType(),
                envelope.aggregateId(), envelope.aggregateVersion(), envelope.correlationId());

        String eventType = envelope.eventType();
        if (eventType.startsWith("workforce.employee.")) {
            replica.apply(envelope);
        } else if (LEAVE_APPROVAL_STARTED.equals(eventType)) {
            leaveReleases.apply(envelope);
        } else if (LEAVE_APPROVED.equals(eventType) || LEAVE_CANCELLED.equals(eventType)) {
            leaveWindows.apply(envelope);
        } else {
            log.warn("Ignoring unexpected event type {}", eventType);
        }
    }
}
