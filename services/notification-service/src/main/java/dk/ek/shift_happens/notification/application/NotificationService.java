package dk.ek.shift_happens.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.notification.domain.EmployeeContact;
import dk.ek.shift_happens.notification.domain.NotificationDraft;
import dk.ek.shift_happens.notification.domain.NotificationPolicy;
import dk.ek.shift_happens.notification.infrastructure.persistence.EmailOutbox;
import dk.ek.shift_happens.notification.infrastructure.persistence.EmployeeContactRepository;
import dk.ek.shift_happens.notification.infrastructure.persistence.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns one consumed event into in-app notifications (and, when the recipient has an address, one
 * {@code notification.email.requested.v1} per notification in the outbox).
 *
 * <p>Everything happens in one local transaction: the {@code processed_event} row, every notification
 * and every email request. A redelivery therefore changes nothing, and a crash cannot leave a
 * notification without its event request (P1.8, P3.2).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** The routing keys of the notification.events queue (contracts/events/catalog.md). */
    public static final Set<String> HANDLED_EVENTS = Set.of(
            "workforce.employee.created.v1", "workforce.employee.updated.v1", "workforce.employee.deleted.v1",
            "scheduling.shift.updated.v1", "scheduling.shift.understaffed.v1",
            "scheduling.assignment.created.v1", "scheduling.assignment.updated.v1",
            "scheduling.swap.requested.v1", "scheduling.swap.approved.v1", "scheduling.swap.rejected.v1",
            "leave.request.submitted.v1", "leave.request.approved.v1", "leave.request.rejected.v1",
            "ai.replacement.suggested.v1");

    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final NotificationRepository notifications;
    private final EmployeeContactRepository contacts;
    private final EmailOutbox outbox;
    private final ObjectMapper mapper;

    public NotificationService(NotificationRepository notifications, EmployeeContactRepository contacts,
                               EmailOutbox outbox, ObjectMapper mapper) {
        this.notifications = notifications;
        this.contacts = contacts;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void handle(JsonNode event) throws JsonProcessingException {
        String eventType = event.path("eventType").asText();
        if (!HANDLED_EVENTS.contains(eventType)) {
            throw new IllegalArgumentException("notification-service does not handle " + eventType);
        }
        UUID eventId = UUID.fromString(event.path("eventId").asText());
        if (notifications.alreadyProcessed(eventId)) {
            log.debug("Skipping redelivered {} ({})", eventType, eventId);
            return;
        }
        // From here on the event is remembered in the same transaction as everything it causes.
        if (eventType.startsWith("workforce.employee.")) {
            if (notifications.remember(eventId)) {
                applyEmployeeEvent(event, eventType);
            }
            return;
        }

        List<NotificationDraft> drafts = draftsFor(eventType, event.path("data"));
        if (!notifications.remember(eventId)) {
            return;
        }
        for (NotificationDraft draft : drafts) {
            long notificationId = notifications.insert(eventId, eventType, draft);
            if (NotificationPolicy.emailRequested(eventType)) {
                requestEmail(event, draft, notificationId);
            }
        }
        log.info("{} produced {} notification(s)", eventType, drafts.size());
    }

    /** Employee replica: email, role and active flag for later notifications (no synchronous call). */
    private void applyEmployeeEvent(JsonNode event, String eventType) {
        long version = event.path("aggregateVersion").asLong();
        JsonNode data = event.path("data");
        if (eventType.endsWith(".deleted.v1")) {
            long employeeId = data.path("entityId").asLong();
            if (employeeId <= 0) throw new IllegalArgumentException("Tombstone without entityId");
            contacts.markDeleted(employeeId, version);
            return;
        }
        long employeeId = data.path("employeeId").asLong();
        if (employeeId <= 0) throw new IllegalArgumentException("Employee snapshot without employeeId");
        contacts.upsert(new EmployeeContact(employeeId, data.path("email").asText(""),
                data.path("userRole").asText("Employee"), data.path("employmentStatus").asText("INACTIVE"), false),
                version);
    }

    /**
     * The business rules, one branch per event. Manager-targeted drafts carry employeeId 0 as a marker and
     * are expanded to every active manager here.
     */
    private List<NotificationDraft> draftsFor(String eventType, JsonNode data) {
        return switch (eventType) {
            case "scheduling.assignment.created.v1" -> List.of(NotificationPolicy.assignmentCreated(
                    employeeId(data), shift(data), when(data, "shiftStartDatetime"), when(data, "shiftEndDatetime")));
            case "scheduling.assignment.updated.v1" -> {
                String status = data.path("assignmentStatus").asText();
                yield NotificationPolicy.assignmentUpdateIsNotified(status)
                        ? List.of(NotificationPolicy.assignmentUpdated(employeeId(data),
                                data.path("shiftAssignmentId").asLong(), status))
                        : List.of();
            }
            case "scheduling.swap.requested.v1" -> List.of(NotificationPolicy.swapRequested(
                    positive(data, "employeeToId"), data.path("shiftSwapId").asLong(),
                    positive(data, "employeeFromId")));
            case "scheduling.swap.approved.v1" -> List.of(
                    NotificationPolicy.swapApprovedForReceiver(positive(data, "employeeToId"),
                            data.path("shiftSwapId").asLong()),
                    NotificationPolicy.swapApprovedForGiver(positive(data, "employeeFromId"),
                            data.path("shiftSwapId").asLong()));
            case "scheduling.swap.rejected.v1" -> List.of(NotificationPolicy.swapRejected(
                    positive(data, "employeeFromId"), data.path("shiftSwapId").asLong(),
                    data.path("reason").asText(null)));
            case "leave.request.submitted.v1" -> List.of(NotificationPolicy.leaveSubmitted(employeeId(data),
                    data.path("leaveTypeName").asText("leave"), data.path("startDate").asText(""),
                    data.path("endDate").asText("")));
            case "leave.request.approved.v1" -> List.of(NotificationPolicy.leaveApproved(employeeId(data),
                    data.path("leaveTypeName").asText("leave"), data.path("startDate").asText(""),
                    data.path("endDate").asText("")));
            case "leave.request.rejected.v1" -> List.of(NotificationPolicy.leaveRejected(employeeId(data),
                    data.path("leaveTypeName").asText("leave"), data.path("startDate").asText(""),
                    data.path("endDate").asText(""), data.path("rejectionReason").asText(null)));
            case "scheduling.shift.updated.v1" -> "CANCELLED".equals(data.path("shiftStatus").asText())
                    ? toManagers(NotificationPolicy.shiftCancelledForManager(shift(data), when(data, "startDatetime")))
                    : List.of();
            case "scheduling.shift.understaffed.v1" -> toManagers(NotificationPolicy.understaffedForManager(
                    data.path("shiftName").asText("Shift " + data.path("shiftId").asLong()),
                    when(data, "startDatetime"), data.path("missingCount").asInt(1)));
            case "ai.replacement.suggested.v1" -> toManagers(NotificationPolicy.suggestionForManager(
                    "Shift " + data.path("shiftId").asLong(), data.path("rankedCandidates").size(),
                    topCandidate(data), data.path("reason").asText("")));
            default -> throw new IllegalArgumentException("Unhandled event type " + eventType);
        };
    }

    private List<NotificationDraft> toManagers(NotificationDraft template) {
        List<EmployeeContact> managers = contacts.activeManagers();
        List<NotificationDraft> drafts = new ArrayList<>(managers.size());
        for (EmployeeContact manager : managers) {
            drafts.add(new NotificationDraft(manager.employeeId(), template.title(), template.message()));
        }
        return drafts;
    }

    /**
     * Adds one email request to the outbox. An employee without a usable address still gets the in-app
     * notification; only the email is skipped.
     */
    private void requestEmail(JsonNode event, NotificationDraft draft, long notificationId)
            throws JsonProcessingException {
        EmployeeContact recipient = contacts.find(draft.employeeId()).orElse(null);
        if (recipient == null || !recipient.emailable()) {
            log.debug("No email address for employee {}; in-app notification {} stays", draft.employeeId(), notificationId);
            return;
        }
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("notificationId", notificationId);
        data.put("to", recipient.email());
        data.put("subject", draft.title());
        data.put("body", draft.message());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "notification.email.requested.v1");
        envelope.put("occurredAt", Instant.now());
        envelope.put("producer", "notification-service");
        envelope.put("aggregateId", notificationId);
        envelope.put("aggregateVersion", 1);
        envelope.put("correlationId", event.path("correlationId").asText(event.path("eventId").asText()));
        envelope.put("causationId", event.path("eventId").asText());
        envelope.put("data", data);
        outbox.add(eventId, mapper.writeValueAsString(envelope));
    }

    private static long employeeId(JsonNode data) {
        return positive(data, "employeeId");
    }

    private static long positive(JsonNode data, String field) {
        long value = data.path(field).asLong();
        if (value <= 0) throw new IllegalArgumentException("Event has no " + field);
        return value;
    }

    private static String shift(JsonNode data) {
        String name = data.path("shiftName").asText("");
        return name.isBlank() ? "shift " + data.path("shiftId").asLong() : name;
    }

    private static String when(JsonNode data, String field) {
        String raw = data.path(field).asText("");
        try {
            return OffsetDateTime.parse(raw).format(HUMAN);
        } catch (RuntimeException notAnInstant) {
            return raw;
        }
    }

    private static String topCandidate(JsonNode data) {
        for (JsonNode candidate : data.path("rankedCandidates")) {
            if (candidate.path("rank").asInt() == 1) {
                return candidate.path("employeeId").asText(null);
            }
        }
        return null;
    }
}
