
package dk.ek.shift_happens.notification.api;

import dk.ek.shift_happens.notification.application.NotificationService;
import dk.ek.shift_happens.notification.infrastructure.persistence.NotificationEntity;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationEntity> getNotifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestHeader("X-Employee-Id") Long employeeId) {

        return notificationService.getNotifications(employeeId, unreadOnly);
    }

    @PatchMapping("/{id}")
    public NotificationEntity updateReadStatus(
            @PathVariable Long id,
            @RequestHeader("X-Employee-Id") Long employeeId,
            @RequestBody UpdateReadStatusRequest request) {

        try {
            return notificationService.updateReadStatus(
                    id, employeeId, request.read());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public record UpdateReadStatusRequest(boolean read) {
    }
}
