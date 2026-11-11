
package dk.ek.shift_happens.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.notification.application.NotificationService;
import org.springframework.stereotype.Component;

@Component
public class AssignmentEventHandler {

    private final NotificationService notificationService;

    public AssignmentEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void handle(EventEnvelope event) {

        JsonNode data = event.data();

        if (data == null
                || !data.hasNonNull("employeeId")
                || !data.hasNonNull("shiftStartDatetime")
                || !data.hasNonNull("shiftEndDatetime")
                || !data.hasNonNull("assignmentStatus")) {
            throw new IllegalArgumentException("Missing required assignment fields");
        }

        Long employeeId = data.get("employeeId").asLong();
        String startDate = data.get("shiftStartDatetime").asText();
        String endDate = data.get("shiftEndDatetime").asText();
        String status = data.get("assignmentStatus").asText();

        String title;
        String notificationMessage;

        switch (event.eventType()) {

            case "scheduling.assignment.created.v1" -> {
                title = "New Shift Assignment";

                notificationMessage = String.format(
                        "You have been assigned to a shift from %s to %s.",
                        startDate,
                        endDate
                );
            }

            case "scheduling.assignment.updated.v1" -> {
                title = "Shift Assignment Updated";

                notificationMessage = String.format(
                        "Your shift assignment from %s to %s has been updated. Status: %s.",
                        startDate,
                        endDate,
                        status
                );
            }

            default -> throw new IllegalArgumentException(
                    "Unsupported assignment event: " + event.eventType()
            );
        }

        var notification = notificationService.createNotification(
                employeeId,
                title,
                notificationMessage,
                event.eventType(),
                event.eventId()
        );

        if (notification != null) {
            System.out.println("Notification saved for employee: " + employeeId);
        } else {
            System.out.println("Notification already exists, skipping.");
        }
    }
}
