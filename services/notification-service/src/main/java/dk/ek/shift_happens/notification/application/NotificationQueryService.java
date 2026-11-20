package dk.ek.shift_happens.notification.application;

import dk.ek.shift_happens.notification.api.NotificationNotFoundException;
import dk.ek.shift_happens.notification.api.NotTheOwnerException;
import dk.ek.shift_happens.notification.domain.StoredNotification;
import dk.ek.shift_happens.notification.infrastructure.persistence.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Read/acknowledge use cases behind the REST controller; ownership is enforced here. */
@Service
public class NotificationQueryService {

    private final NotificationRepository repository;

    public NotificationQueryService(NotificationRepository repository) {
        this.repository = repository;
    }

    public List<StoredNotification> findFor(long employeeId, boolean unreadOnly) {
        return repository.findForEmployee(employeeId, unreadOnly);
    }

    @Transactional
    public StoredNotification setRead(long notificationId, long callerEmployeeId, boolean read) {
        StoredNotification notification = repository.find(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));
        if (notification.employeeId() != callerEmployeeId) {
            throw new NotTheOwnerException();
        }
        repository.setRead(notificationId, callerEmployeeId, read);
        return repository.find(notificationId).orElseThrow(() -> new NotificationNotFoundException(notificationId));
    }
}
