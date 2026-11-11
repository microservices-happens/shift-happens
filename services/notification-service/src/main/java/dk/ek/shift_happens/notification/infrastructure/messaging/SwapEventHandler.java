
package dk.ek.shift_happens.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.notification.application.NotificationService;
import org.springframework.stereotype.Component;

@Component
public class SwapEventHandler {

    private final NotificationService notificationService;

    public SwapEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    public void handle(EventEnvelope event) {

        JsonNode data = event.data();

        if (data == null
                || !data.hasNonNull("shiftSwapId")
                || !data.hasNonNull("shiftId")
                || !data.hasNonNull("employeeFromId")
                || !data.hasNonNull("employeeToId")
                || !data.hasNonNull("swapStatus")) {
            throw new IllegalArgumentException("Missing required swap fields");
        }

        Long employeeFromId = data.get("employeeFromId").asLong();
        Long employeeToId = data.get("employeeToId").asLong();
        Long shiftId = data.get("shiftId").asLong();

        switch (event.eventType()) {

            case "scheduling.swap.requested.v1" -> {
                notificationService.createNotification(
                        employeeToId,
                        "Shift Swap Requested",
                        "Employee " + employeeFromId
                                + " has requested a shift swap with you for shift "
                                + shiftId + ".",
                        event.eventType(),
                        event.eventId()
                );
            }

            case "scheduling.swap.approved.v1" -> {
                notifyBothEmployees(
                        employeeFromId,
                        employeeToId,
                        "Shift Swap Approved",
                        "Your shift swap for shift " + shiftId
                                + " has been approved.",
                        event
                );
            }

            case "scheduling.swap.rejected.v1" -> {
                notifyBothEmployees(
                        employeeFromId,
                        employeeToId,
                        "Shift Swap Rejected",
                        "Your shift swap for shift " + shiftId
                                + " has been rejected.",
                        event
                );
            }

            default -> throw new IllegalArgumentException(
                    "Unsupported swap event: " + event.eventType()
            );
        }
    }

    private void notifyBothEmployees(
            Long employeeFromId,
            Long employeeToId,
            String title,
            String message,
            EventEnvelope event
    ) {

        notificationService.createNotification(
                employeeFromId,
                title,
                message,
                event.eventType(),
                event.eventId()
        );

        // Avoid notifying the same employee twice.
        if (!employeeFromId.equals(employeeToId)) {
            notificationService.createNotification(
                    employeeToId,
                    title,
                    message,
                    event.eventType(),
                    event.eventId()
            );
        }
    }
}
