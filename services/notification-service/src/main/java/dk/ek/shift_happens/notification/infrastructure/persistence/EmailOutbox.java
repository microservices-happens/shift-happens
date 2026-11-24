package dk.ek.shift_happens.notification.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Transactional outbox: an email request is committed with the notification it belongs to (P1.8). */
@Component
public class EmailOutbox {

    private final JdbcTemplate jdbc;

    public EmailOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(UUID eventId, String payload) {
        jdbc.update("INSERT INTO outbox_event (event_id,event_type,payload) VALUES (?,?,?)",
                eventId, "notification.email.requested.v1", payload);
    }
}
