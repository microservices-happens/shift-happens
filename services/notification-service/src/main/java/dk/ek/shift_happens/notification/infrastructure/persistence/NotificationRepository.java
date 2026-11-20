package dk.ek.shift_happens.notification.infrastructure.persistence;

import dk.ek.shift_happens.notification.domain.NotificationDraft;
import dk.ek.shift_happens.notification.domain.StoredNotification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * In-app notifications and the idempotency table. Every method runs inside the caller's transaction, so
 * the {@code processed_event} row, the notifications and their outgoing email requests commit together.
 */
@Component
public class NotificationRepository {

    private static final RowMapper<StoredNotification> MAPPER = (rs, row) -> new StoredNotification(
            rs.getLong("notification_id"), rs.getLong("employee_id"), rs.getString("title"),
            rs.getString("message"), rs.getString("source_event_type"),
            rs.getObject("source_event_id", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getBoolean("is_read"));

    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent consumer: true the first time this eventId is seen, false on a redelivery. */
    public boolean remember(UUID eventId) {
        return jdbc.update("INSERT INTO processed_event(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId) > 0;
    }

    public boolean alreadyProcessed(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM processed_event WHERE event_id=?)", Boolean.class, eventId));
    }

    public long insert(UUID sourceEventId, String sourceEventType, NotificationDraft draft) {
        Long id = jdbc.queryForObject("""
                INSERT INTO notification (employee_id,title,message,source_event_type,source_event_id)
                VALUES (?,?,?,?,?)
                ON CONFLICT (source_event_id, employee_id) DO NOTHING
                RETURNING notification_id
                """, Long.class, draft.employeeId(), draft.title(), draft.message(), sourceEventType, sourceEventId);
        if (id != null) {
            return id;
        }
        // The unique key already held this event and recipient (a redelivery that slipped past processed_event).
        return jdbc.queryForObject("SELECT notification_id FROM notification WHERE source_event_id=? AND employee_id=?",
                Long.class, sourceEventId, draft.employeeId());
    }

    public List<StoredNotification> findForEmployee(long employeeId, boolean unreadOnly) {
        String sql = "SELECT * FROM notification WHERE employee_id=?"
                + (unreadOnly ? " AND is_read=FALSE" : "") + " ORDER BY created_at DESC, notification_id DESC";
        return jdbc.query(sql, MAPPER, employeeId);
    }

    public Optional<StoredNotification> find(long notificationId) {
        return jdbc.query("SELECT * FROM notification WHERE notification_id=?", MAPPER, notificationId)
                .stream().findFirst();
    }

    public boolean markRead(long notificationId, long employeeId) {
        return setRead(notificationId, employeeId, true);
    }

    public boolean setRead(long notificationId, long employeeId, boolean read) {
        return jdbc.update("UPDATE notification SET is_read=? WHERE notification_id=? AND employee_id=?",
                read, notificationId, employeeId) > 0;
    }
}
