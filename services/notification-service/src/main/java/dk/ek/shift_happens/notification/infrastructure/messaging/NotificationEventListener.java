
package dk.ek.shift_happens.notification.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventListener {

    private final ObjectMapper objectMapper;
    private final LeaveRequestEventHandler leaveRequestEventHandler;
    private final AssignmentEventHandler assignmentEventHandler;
    private final SwapEventHandler swapEventHandler;

    public NotificationEventListener(
            ObjectMapper objectMapper,
            LeaveRequestEventHandler leaveRequestEventHandler,
            AssignmentEventHandler assignmentEventHandler,
            SwapEventHandler swapEventHandler
    ) {
        this.objectMapper = objectMapper;
        this.leaveRequestEventHandler = leaveRequestEventHandler;
        this.assignmentEventHandler = assignmentEventHandler;
        this.swapEventHandler = swapEventHandler;
    }

    @RabbitListener(queues = "${RABBITMQ_QUEUE:notification.events}")
    public void receiveEvent(String message) throws JsonProcessingException {

        EventEnvelope event = objectMapper.readValue(
                message,
                EventEnvelope.class
        );

        System.out.println("Received event: " + event.eventType());

        switch (event.eventType()) {

            case "leave.request.approved.v1",
                 "leave.request.rejected.v1" ->
                    leaveRequestEventHandler.handle(event);

            case "scheduling.assignment.created.v1",
                 "scheduling.assignment.updated.v1" ->
                    assignmentEventHandler.handle(event);

            case "scheduling.swap.requested.v1",
                 "scheduling.swap.approved.v1",
                 "scheduling.swap.rejected.v1" ->
                    swapEventHandler.handle(event);

            default ->
                    System.out.println(
                            "No handler implemented for event: "
                                    + event.eventType()
                    );
        }
    }
}
