package dk.ek.shift_happens.scheduling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.scheduling.domain.LeaveWindow;
import dk.ek.shift_happens.scheduling.domain.LeaveWindowRepository;
import dk.ek.shift_happens.scheduling.domain.LeaveWindowStatus;
import dk.ek.shift_happens.scheduling.domain.ProcessedEventLog;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps a copy of approved leave windows, so the understaffed candidates can leave out employees who
 * are away without calling Leave (contracts/events/catalog.md).
 *
 * <p>leave.request.approved.v1 adds or refreshes a window and leave.request.cancelled.v1 closes it.
 * Idempotent on eventId, and a lower aggregateVersion never overwrites a higher one.
 */
@Service
@Profile("command")
public class LeaveWindowService {

    private static final Logger log = LoggerFactory.getLogger(LeaveWindowService.class);

    private final LeaveWindowRepository windows;
    private final ProcessedEventLog processedEvents;
    private final ObjectMapper objectMapper;

    LeaveWindowService(LeaveWindowRepository windows, ProcessedEventLog processedEvents,
                       ObjectMapper objectMapper) {
        this.windows = windows;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(EventEnvelope envelope) {
        if (processedEvents.contains(envelope.eventId())) {
            log.debug("Ignoring already processed event {}", envelope.eventId());
            return;
        }

        LeaveRequestSnapshot request = objectMapper.convertValue(envelope.data(), LeaveRequestSnapshot.class);
        if (request.leaveRequestId() == null || request.employeeId() == null || request.startDate() == null
                || request.endDate() == null) {
            throw new IllegalArgumentException("leave event " + envelope.eventId()
                    + " has no leaveRequestId, employeeId, startDate or endDate");
        }

        LeaveWindowStatus status = envelope.eventType().endsWith(".cancelled.v1")
                ? LeaveWindowStatus.CANCELLED
                : LeaveWindowStatus.APPROVED;

        LeaveWindow current = windows.findById(request.leaveRequestId()).orElse(null);
        if (current == null || current.accepts(envelope.aggregateVersion())) {
            windows.save(new LeaveWindow(request.leaveRequestId(), request.employeeId(), request.startDate(),
                    request.endDate(), status, envelope.aggregateVersion()));
        } else {
            log.info("Ignoring {} for leave request {}: version {} is not newer than {}",
                    envelope.eventType(), request.leaveRequestId(), envelope.aggregateVersion(),
                    current.aggregateVersion());
        }
        processedEvents.record(envelope.eventId());
    }
}
