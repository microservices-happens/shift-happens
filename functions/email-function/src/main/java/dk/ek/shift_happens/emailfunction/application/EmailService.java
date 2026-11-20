package dk.ek.shift_happens.emailfunction.application;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends one email per {@code notification.email.requested.v1} message.
 *
 * <p>Deduplication is in memory, bounded to the last {@value #REMEMBERED} event ids: a redelivery in the
 * same process sends nothing twice. Across restarts the function is at-most-once per message only if SMTP
 * accepted the message before the crash; e-mail delivery itself is not transactional (see README,
 * "Limitations", and the report's improvements section).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    static final int REMEMBERED = 10_000;

    private final JavaMailSender mail;
    private final String from;
    private final AtomicBoolean handledSomething = new AtomicBoolean(false);
    private final Map<UUID, Boolean> processed = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > REMEMBERED;
                }
            });

    public EmailService(JavaMailSender mail, @Value("${app.mail.from}") String from) {
        this.mail = mail;
        this.from = from;
    }

    /**
     * Handles one message. Throws for an unusable payload or an SMTP failure, so the listener retries three
     * times and then dead-letters it; the message is only remembered after SMTP accepted it.
     */
    public void handle(JsonNode envelope) {
        String eventType = envelope.path("eventType").asText();
        if (!"notification.email.requested.v1".equals(eventType)) {
            throw new IllegalArgumentException("email-function does not handle " + eventType);
        }
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        if (processed.containsKey(eventId)) {
            log.debug("Skipping redelivered email request {}", eventId);
            return;
        }
        JsonNode data = envelope.path("data");
        String to = required(data, "to");
        String subject = required(data, "subject");
        String body = required(data, "body");
        requireAddress(to);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mail.send(message);
        } catch (MailException failure) {
            // The exception type only: the message contains a recipient address and body text.
            throw new IllegalStateException("SMTP rejected the email (" + failure.getClass().getSimpleName() + ")");
        }
        processed.put(eventId, Boolean.TRUE);
        handledSomething.set(true);
        log.info("Sent notification {} to {} ({})", data.path("notificationId").asLong(), to, eventId);
    }

    /** True after at least one email was sent; the idle exit waits for that before it closes the process. */
    public boolean handledSomething() {
        return handledSomething.get();
    }

    private static String required(JsonNode data, String field) {
        String value = data.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Email request has no " + field);
        }
        return value;
    }

    private static void requireAddress(String address) {
        try {
            InternetAddress parsed = new InternetAddress(address, true);
            parsed.validate();
        } catch (AddressException e) {
            throw new IllegalArgumentException("Email request has an invalid recipient address");
        }
    }
}
