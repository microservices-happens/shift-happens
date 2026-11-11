
package dk.ek.shift_happens.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.notification.application.NotificationService;
import org.springframework.stereotype.Component;

@Component
public class LeaveRequestEventHandler {

    private final NotificationService notificationService;

    public LeaveRequestEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void handle(EventEnvelope event) {

        JsonNode data = event.data();

        if (data == null
                || !data.hasNonNull("employeeId")
                || !data.hasNonNull("leaveTypeName")
                || !data.hasNonNull("startDate")
                || !data.hasNonNull("endDate")) {
            throw new IllegalArgumentException("Missing required leave request fields");
        }

        Long employeeId = data.get("employeeId").asLong();
        String leaveType = data.get("leaveTypeName").asText();
        String startDate = data.get("startDate").asText();
        String endDate = data.get("endDate").asText();

        String title;
        String notificationMessage;

        switch (event.eventType()) {

            case "leave.request.approved.v1" -> {
                title = "Leave Request Approved";
                notificationMessage = String.format(
                        "Your %s request from %s to %s has been approved.",
                        leaveType, startDate, endDate
                );
            }

            case "leave.request.rejected.v1" -> {
                title = "Leave Request Rejected";
                notificationMessage = String.format(
                        "Your %s request from %s to %s has been rejected.",
                        leaveType, startDate, endDate
                );
            }

            default -> throw new IllegalArgumentException(
                    "Unsupported leave event: " + event.eventType()
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
