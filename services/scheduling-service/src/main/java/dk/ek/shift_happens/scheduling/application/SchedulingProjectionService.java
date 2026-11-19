package dk.ek.shift_happens.scheduling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.domain.AssignmentSnapshot;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeSnapshot;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.ProjectionEvent;
import dk.ek.shift_happens.scheduling.domain.ProjectionEventStore;
import dk.ek.shift_happens.scheduling.domain.RankedCandidate;
import dk.ek.shift_happens.scheduling.domain.ReadModelProjection;
import dk.ek.shift_happens.scheduling.domain.ShiftSnapshot;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionSource;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import dk.ek.shift_happens.scheduling.domain.SwapSnapshot;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import dk.ek.shift_happens.scheduling.domain.SwapView;
import dk.ek.shift_happens.scheduling.domain.Tombstone;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds the Mongo read model from events (P2.10, the query half of CQRS).
 *
 * <p>Three guarantees, in the order they are applied: a redelivered event is recognised by its eventId
 * and skipped; the snapshot is applied with the version rule of the adapter, so arrival order does not
 * matter and a tombstone is never cleared; and the event is appended to the projection log, which is
 * what the rebuild replays. The log is written after the change, so a crash in between only re-applies
 * the same snapshot, which the version rule turns into a no-op.
 */
@Service
@Profile("query")
public class SchedulingProjectionService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingProjectionService.class);

    static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";
    static final String EMPLOYEE_UPDATED = "workforce.employee.updated.v1";
    static final String EMPLOYEE_DELETED = "workforce.employee.deleted.v1";
    static final String AI_SUGGESTED = "ai.replacement.suggested.v1";

    private final ReadModelProjection readModel;
    private final ProjectionEventStore eventStore;
    private final ObjectMapper objectMapper;

    SchedulingProjectionService(ReadModelProjection readModel, ProjectionEventStore eventStore,
                                ObjectMapper objectMapper) {
        this.readModel = readModel;
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
    }

    /** Live path: one message from the scheduling.projection queue. */
    public void apply(EventEnvelope envelope) {
        if (eventStore.isProcessed(envelope.eventId())) {
            log.debug("Ignoring already projected event {}", envelope.eventId());
            return;
        }
        project(envelope);
        eventStore.append(new ProjectionEvent(envelope.eventId(), envelope.eventType(), envelope.producer(),
                envelope.aggregateId(), envelope.aggregateVersion(), envelope.occurredAt(),
                envelope.correlationId(), envelope.causationId(), envelope.data()));
        eventStore.markProcessed(envelope.eventId());
    }

    /**
     * Rebuild (P2.10): empty the read-model collections and replay every logged event into them. The log
     * itself is kept, so a rebuild can be repeated.
     */
    public long rebuild() {
        List<ProjectionEvent> events = eventStore.replayOrder();
        readModel.clear();
        eventStore.clearProcessed();
        for (ProjectionEvent event : events) {
            project(new EventEnvelope(event.eventId(), event.eventType(), event.occurredAt(), event.producer(),
                    event.aggregateId(), event.aggregateVersion(), event.correlationId(), event.causationId(),
                    event.payload()));
            eventStore.markProcessed(event.eventId());
        }
        log.info("Rebuilt the scheduling read model from {} logged events", events.size());
        return events.size();
    }
    private void project(EventEnvelope envelope) {
        switch (envelope.eventType()) {
            case DomainEvents.SHIFT_CREATED, DomainEvents.SHIFT_UPDATED -> projectShift(envelope);
            case DomainEvents.SHIFT_DELETED -> {
                Tombstone tombstone = payload(envelope, Tombstone.class);
                readModel.deleteShift(tombstone.entityId(), envelope.aggregateVersion(), tombstone.deletedAt());
            }
            case DomainEvents.ASSIGNMENT_CREATED, DomainEvents.ASSIGNMENT_UPDATED -> projectAssignment(envelope);
            case DomainEvents.ASSIGNMENT_DELETED -> {
                Tombstone tombstone = payload(envelope, Tombstone.class);
                readModel.deleteAssignment(tombstone.entityId(), envelope.aggregateVersion(),
                        tombstone.deletedAt());
            }
            case DomainEvents.SWAP_REQUESTED, DomainEvents.SWAP_APPROVED, DomainEvents.SWAP_REJECTED ->
                    projectSwap(envelope);
            case EMPLOYEE_CREATED, EMPLOYEE_UPDATED -> projectEmployee(envelope);
            case EMPLOYEE_DELETED -> {
                Tombstone tombstone = payload(envelope, Tombstone.class);
                readModel.deleteEmployee(tombstone.entityId(), envelope.aggregateVersion(),
                        tombstone.deletedAt());
            }
            case AI_SUGGESTED -> projectSuggestion(envelope);
            default -> log.debug("No read-model effect for {}", envelope.eventType());
        }
    }

    private void projectShift(EventEnvelope envelope) {
        ShiftSnapshot snapshot = payload(envelope, ShiftSnapshot.class);
        readModel.upsertShift(new ShiftView(snapshot.shiftId(), snapshot.departmentId(),
                snapshot.workLocationId(), snapshot.shiftName(), snapshot.startDatetime(),
                snapshot.endDatetime(), ShiftStatus.valueOf(snapshot.shiftStatus()),
                snapshot.requiredJobRoles(), envelope.aggregateVersion(), null));
    }

    private void projectAssignment(EventEnvelope envelope) {
        AssignmentSnapshot snapshot = payload(envelope, AssignmentSnapshot.class);
        readModel.upsertAssignment(new AssignmentView(snapshot.shiftAssignmentId(), snapshot.shiftId(),
                snapshot.employeeId(), AssignmentStatus.valueOf(snapshot.assignmentStatus()),
                snapshot.assignedDatetime(), snapshot.checkInDatetime(), snapshot.checkOutDatetime(),
                snapshot.shiftStartDatetime(), snapshot.shiftEndDatetime(), envelope.aggregateVersion(), null));
    }

    private void projectSwap(EventEnvelope envelope) {
        SwapSnapshot snapshot = payload(envelope, SwapSnapshot.class);
        readModel.upsertSwap(new SwapView(snapshot.shiftSwapId(), snapshot.originalShiftAssignmentId(),
                snapshot.shiftId(), snapshot.employeeFromId(), snapshot.employeeToId(),
                SwapStatus.valueOf(snapshot.swapStatus()), snapshot.reason(), snapshot.requestDatetime(),
                snapshot.decision(), envelope.aggregateVersion()));
    }

    private void projectEmployee(EventEnvelope envelope) {
        EmployeeSnapshot snapshot = payload(envelope, EmployeeSnapshot.class);
        readModel.upsertEmployee(new EmployeeView(snapshot.employeeId(), snapshot.firstName(),
                snapshot.lastName(), snapshot.jobRoleIds() == null ? List.of() : snapshot.jobRoleIds(),
                snapshot.employmentStatus(),
                snapshot.weeklyContractHours() == null ? 0 : snapshot.weeklyContractHours(),
                envelope.aggregateVersion(), null));
    }

    private void projectSuggestion(EventEnvelope envelope) {
        ReplacementSuggestionPayload suggestion = payload(envelope, ReplacementSuggestionPayload.class);
        List<RankedCandidate> candidates = suggestion.rankedCandidates() == null ? List.of()
                : suggestion.rankedCandidates().stream()
                        .map(candidate -> new RankedCandidate(candidate.employeeId(), candidate.rank()))
                        .toList();
        readModel.upsertSuggestion(new SuggestionView(suggestion.shiftId(), suggestion.jobRoleId(),
                SuggestionSource.of(suggestion.source()), suggestion.model(), envelope.occurredAt(),
                suggestion.reason(), candidates));
    }

    private <T> T payload(EventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.convertValue(envelope.data(), type);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("event " + envelope.eventId() + " (" + envelope.eventType()
                    + ") does not match its payload schema: " + invalid.getMessage(), invalid);
        }
    }
}
