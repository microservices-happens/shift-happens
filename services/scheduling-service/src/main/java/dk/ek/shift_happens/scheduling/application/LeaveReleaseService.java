package dk.ek.shift_happens.scheduling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.LeaveReleaseRecord;
import dk.ek.shift_happens.scheduling.domain.LeaveReleaseRepository;
import dk.ek.shift_happens.scheduling.domain.LeaveReleaseRule;
import dk.ek.shift_happens.scheduling.domain.LeaveReleaseStatus;
import dk.ek.shift_happens.scheduling.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Scheduling half of the leave-approval saga (P3.1, contracts/events/catalog.md).
 *
 * <p>On leave.request.approval-started.v1 it releases the employee's overlapping assignments and
 * answers scheduling.leave-release.completed.v1, or - when any of those shifts is locked - answers
 * scheduling.leave-release.rejected.v1 and changes nothing, so Leave compensates. One transaction,
 * and the answer is stored so a redelivered request republishes the same reply.
 */
@Service
@Profile("command")
public class LeaveReleaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaveReleaseService.class);

    private final ShiftRepository shifts;
    private final ShiftAssignmentRepository assignments;
    private final LeaveReleaseRepository releases;
    private final DomainEvents events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    LeaveReleaseService(ShiftRepository shifts, ShiftAssignmentRepository assignments,
                        LeaveReleaseRepository releases, DomainEvents events, ObjectMapper objectMapper,
                        Clock clock) {
        this.shifts = shifts;
        this.assignments = assignments;
        this.releases = releases;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void apply(EventEnvelope envelope) {
        long leaveRequestId = envelope.aggregateId();

        Optional<LeaveReleaseRecord> answered = releases.findById(leaveRequestId);
        if (answered.isPresent()) {
            log.info("Leave request {} was already answered; republishing the reply", leaveRequestId);
            publishReply(answered.get(), envelope);
            return;
        }

        LeaveRequestSnapshot request = objectMapper.convertValue(envelope.data(), LeaveRequestSnapshot.class);
        if (request.employeeId() == null || request.startDate() == null || request.endDate() == null) {
            throw new IllegalArgumentException("leave request " + leaveRequestId
                    + " has no employeeId, startDate or endDate");
        }

        // The contract carries whole days, so the window is [startDate 00:00Z, endDate+1 00:00Z).
        Instant from = request.startDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = request.endDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = clock.instant();

        List<ShiftAssignment> overlapping = assignments.findActiveOverlapping(request.employeeId(), from, to);
        LeaveReleaseRecord record = rejectIfLocked(leaveRequestId, request, overlapping, now);

        if (record == null) {
            List<Long> released = new ArrayList<>();
            for (ShiftAssignment assignment : overlapping) {
                Shift shift = shiftOf(assignment);
                ShiftAssignment freed = assignments.save(
                        assignment.withStatus(AssignmentStatus.RELEASED_FOR_LEAVE, null, null));
                released.add(freed.shiftAssignmentId());
                events.append(DomainEvents.ASSIGNMENT_UPDATED, freed.shiftAssignmentId(),
                        freed.aggregateVersion(), envelope.correlationId(), envelope.eventId(),
                        freed.toSnapshot(shift));
            }
            record = LeaveReleaseRecord.completed(leaveRequestId, request.employeeId(), request.startDate(),
                    request.endDate(), released, now);
        }

        releases.save(record);
        publishReply(record, envelope);
    }

    /** A locked shift blocks the whole release: the saga compensates instead of half-releasing. */
    private LeaveReleaseRecord rejectIfLocked(long leaveRequestId, LeaveRequestSnapshot request,
                                               List<ShiftAssignment> overlapping, Instant now) {
        for (ShiftAssignment assignment : overlapping) {
            Shift shift = shiftOf(assignment);
            if (LeaveReleaseRule.isLocked(shift, assignment, now)) {
                String reason = "shift " + shift.shiftId() + " (" + shift.shiftName()
                        + ") starts within 24 hours";
                return LeaveReleaseRecord.rejected(leaveRequestId, request.employeeId(), request.startDate(),
                        request.endDate(), reason, now);
            }
        }
        return null;
    }

    private void publishReply(LeaveReleaseRecord record, EventEnvelope cause) {
        String eventType = record.result() == LeaveReleaseStatus.COMPLETED
                ? DomainEvents.LEAVE_RELEASE_COMPLETED
                : DomainEvents.LEAVE_RELEASE_REJECTED;
        events.append(eventType, record.leaveRequestId(), 1, cause.correlationId(), cause.eventId(),
                record.payload());
    }

    private Shift shiftOf(ShiftAssignment assignment) {
        return shifts.findById(assignment.shiftId())
                .orElseThrow(() -> new ShiftNotFoundException(assignment.shiftId()));
    }
}
