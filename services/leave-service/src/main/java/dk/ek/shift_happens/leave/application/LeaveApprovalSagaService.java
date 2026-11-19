package dk.ek.shift_happens.leave.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.leave.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.leave.domain.InsufficientLeaveBalanceException;
import dk.ek.shift_happens.leave.domain.LeaveApprovalSaga;
import dk.ek.shift_happens.leave.domain.LeaveApprovalSagaRepository;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.LeaveRequestRejectedException;
import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import dk.ek.shift_happens.leave.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.leave.domain.LeaveRequestStatus;
import dk.ek.shift_happens.leave.domain.LeaveTypeRepository;
import dk.ek.shift_happens.leave.domain.ProcessedEventLog;
import dk.ek.shift_happens.leave.domain.SagaStatus;
import dk.ek.shift_happens.leave.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrator of the leave-approval saga (P3.1, contracts/events/catalog.md, "Flow: leave-approval saga").
 *
 * <p>Every path locks the request row first (the ledger steps then lock the employee), checks the state of the
 * saga attempt under that lock and only then acts, so a reply, a timeout and a cancel of the same request are
 * serialized and exactly one of them decides the outcome:
 * <ul>
 *   <li>{@link #begin}: approve (and retry) publishes approval-started and stores the attempt with its deadline;</li>
 *   <li>{@link #onReply}: Scheduling's completed reply approves, its rejected reply compensates; a late completed
 *       reply after a timeout compensation retries as the next attempt;</li>
 *   <li>{@link #timeOut}: no reply before the deadline compensates.</li>
 * </ul>
 * Compensation is a REVERSAL ledger entry plus REJECTED with a reason, and always publishes
 * leave.request.rejected.v1. The consumer is idempotent: the eventId goes to processed_events in the same transaction.
 */
@Service
@Profile("command")
public class LeaveApprovalSagaService {

    static final String TIMEOUT_REASON = "Timed out waiting for Scheduling to release the shifts";
    static final String DEFAULT_REFUSAL_REASON = "Scheduling could not release the employee's shifts";

    private static final Logger log = LoggerFactory.getLogger(LeaveApprovalSagaService.class);

    private final LeaveApprovalSagaRepository sagas;
    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;
    private final EmployeeReplicaRepository employees;
    private final LeaveLedgerService ledger;
    private final ProcessedEventLog processedEvents;
    private final DomainEvents events;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration timeout;

    LeaveApprovalSagaService(LeaveApprovalSagaRepository sagas, LeaveRequestRepository requests,
                             LeaveTypeRepository types, EmployeeReplicaRepository employees,
                             LeaveLedgerService ledger,
                             ProcessedEventLog processedEvents, DomainEvents events, ObjectMapper objectMapper,
                             Clock clock, @Value("${app.saga.timeout}") String timeout) {
        this.sagas = sagas;
        this.requests = requests;
        this.types = types;
        this.employees = employees;
        this.ledger = ledger;
        this.processedEvents = processedEvents;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.timeout = Duration.parse(timeout);
        if (this.timeout.isZero() || this.timeout.isNegative()) {
            throw new IllegalArgumentException("SAGA_TIMEOUT (app.saga.timeout) must be a positive ISO-8601 duration "
                    + "such as PT5M, was " + timeout);
        }
    }

    /**
     * Starts attempt {@code attempt}: saves the request in its new APPROVAL_PENDING state, publishes
     * leave.request.approval-started.v1 and stores the attempt with its deadline. The caller has already locked the
     * request row and reserved the days for this attempt, and runs in one transaction with this call.
     */
    public void begin(LeaveRequest approvalPending, int attempt, String correlationId, UUID causationId) {
        LeaveRequest saved = requests.save(approvalPending);
        EventEnvelope started = events.append(DomainEvents.LEAVE_REQUEST_APPROVAL_STARTED, saved.leaveRequestId(),
                saved.aggregateVersion(), correlationId, causationId, snapshot(saved));
        sagas.save(LeaveApprovalSaga.start(saved.leaveRequestId(), attempt, started.eventId(),
                started.correlationId(), clock.instant(), timeout));
    }

    /** scheduling.leave-release.completed.v1 / .rejected.v1. Malformed replies throw and end up dead-lettered. */
    @Transactional
    public void onReply(EventEnvelope envelope, LeaveApprovalSaga.Reply reply) {
        long leaveRequestId = envelope.aggregateId();
        requireWellFormed(envelope, leaveRequestId);

        Optional<LeaveRequest> locked = requests.findByIdForUpdate(leaveRequestId);
        if (processedEvents.contains(envelope.eventId())) {
            log.debug("Ignoring already processed reply {}", envelope.eventId());
            return;
        }
        processedEvents.record(envelope.eventId());
        if (locked.isEmpty()) {
            log.warn("Ignoring {} {}: unknown leave request {}", envelope.eventType(), envelope.eventId(),
                    leaveRequestId);
            return;
        }
        LeaveRequest request = locked.get();
        Optional<LeaveApprovalSaga> attempt = correlate(envelope, leaveRequestId);
        if (attempt.isEmpty()) {
            log.warn("Ignoring {} {}: no saga attempt of leave request {} matches causationId {}",
                    envelope.eventType(), envelope.eventId(), leaveRequestId, envelope.causationId());
            return;
        }
        LeaveApprovalSaga saga = attempt.get();
        boolean latest = sagas.findLatest(leaveRequestId).map(l -> l.attempt() == saga.attempt()).orElse(false);

        switch (saga.reactTo(reply, latest)) {
            case COMPLETE -> complete(request, saga, envelope);
            case COMPENSATE -> compensate(request, saga, reasonOf(envelope), envelope.eventId(), envelope);
            case RETRY -> retry(request, saga, envelope);
            case IGNORE -> {
                if (saga.status() == SagaStatus.TIMED_OUT && reply == LeaveApprovalSaga.Reply.COMPLETED && latest) {
                    log.warn("Leave request {} stays REJECTED although Scheduling released the employee's shifts: "
                            + "completed reply {} for timed-out attempt {} arrived after the last allowed attempt "
                            + "(correlationId {}); the released assignments need manual follow-up", leaveRequestId,
                            envelope.eventId(), saga.attempt(), saga.correlationId());
                } else {
                    log.info("Ignoring {} for attempt {} of leave request {} ({}): nothing to do",
                            envelope.eventType(), saga.attempt(), leaveRequestId, saga.status());
                }
            }
        }
    }

    /**
     * The deadline of an attempt passed. The candidate was read without a lock, so everything is re-checked under the
     * request's row lock; a row that is locked right now (a reply is being applied) is skipped and tried again by the
     * next poll. Returns true when the attempt was compensated.
     */
    @Transactional
    public boolean timeOut(long sagaId, long leaveRequestId) {
        Optional<LeaveRequest> locked = requests.findByIdForUpdateSkipLocked(leaveRequestId);
        if (locked.isEmpty()) {
            return false;
        }
        Optional<LeaveApprovalSaga> current = sagas.findById(sagaId); // read after the lock: sees committed replies
        if (current.isEmpty() || !current.get().isOverdue(clock.instant())) {
            return false;
        }
        compensate(locked.get(), current.get(), TIMEOUT_REASON, null, null);
        return true;
    }

    private void complete(LeaveRequest request, LeaveApprovalSaga saga, EventEnvelope reply) {
        LeaveRequest approved = requests.save(request.approved());
        sagas.save(saga.completed(clock.instant()));
        events.append(DomainEvents.LEAVE_REQUEST_APPROVED, approved.leaveRequestId(), approved.aggregateVersion(),
                saga.correlationId(), reply.eventId(), snapshot(approved));
    }

    /** REVERSAL, REJECTED with the reason, rejected.v1. causationId is the reply's eventId, or null for a timeout. */
    private void compensate(LeaveRequest request, LeaveApprovalSaga saga, String reason, UUID causationId,
                            EventEnvelope reply) {
        Optional<?> reversal = ledger.reverseReservation(request.leaveRequestId(), saga.attempt(),
                saga.correlationId(), causationId);
        boolean fromReply = reply != null;
        if (reversal.isEmpty()) {
            throw new IllegalStateException("attempt " + saga.attempt() + " of leave request "
                    + request.leaveRequestId() + " is STARTED but has no open reservation");
        }
        LeaveRequest rejected = requests.save(request.rejectedBySaga(reason));
        sagas.save(fromReply ? saga.compensated(clock.instant()) : saga.timedOut(clock.instant()));
        events.append(DomainEvents.LEAVE_REQUEST_REJECTED, rejected.leaveRequestId(), rejected.aggregateVersion(),
                saga.correlationId(), causationId, snapshot(rejected));
    }

    /**
     * A completed reply of a timed-out attempt: Scheduling did release the employee after all, so the request must
     * not silently become APPROVED (its days were given back). Reserve again and start the next attempt; Scheduling
     * answers the repeated approval-started with its stored reply. Without balance, with a new overlapping request
     * or for an employee who is no longer active, the request stays REJECTED.
     */
    private void retry(LeaveRequest request, LeaveApprovalSaga saga, EventEnvelope reply) {
        if (request.status() != LeaveRequestStatus.REJECTED) {
            log.warn("Not retrying attempt {} of leave request {}: the request is {}", saga.attempt(),
                    request.leaveRequestId(), request.status());
            return;
        }
        int next = saga.attempt() + 1;
        requests.lockEmployeeCalendar(request.employeeId()); // request row, then employee: the usual order
        try {
            LeaveRequestService.requireActiveEmployee(employees, request.employeeId(), "employee");
            request.approvalRetried().requireNoOverlapWith(requests.findOccupyingByEmployeeId(request.employeeId()));
            ledger.reserve(request.leaveRequestId(), next, saga.correlationId(), reply.eventId());
        } catch (InsufficientLeaveBalanceException | LeaveRequestRejectedException e) {
            log.warn("Not retrying leave request {} after the late completed reply: it stays REJECTED although "
                    + "Scheduling released the employee's shifts (correlationId {}): {}; the released assignments "
                    + "need manual follow-up", request.leaveRequestId(), saga.correlationId(), e.getMessage());
            return;
        }
        begin(request.approvalRetried(), next, saga.correlationId(), reply.eventId());
    }

    /** The attempt a reply answers: the one whose approval-started eventId is the causationId. */
    private Optional<LeaveApprovalSaga> correlate(EventEnvelope envelope, long leaveRequestId) {
        if (envelope.causationId() == null) {
            return sagas.findStarted(leaveRequestId);
        }
        return sagas.findByApprovalStartedEventId(envelope.causationId())
                .filter(saga -> saga.leaveRequestId() == leaveRequestId);
    }

    private void requireWellFormed(EventEnvelope envelope, long leaveRequestId) {
        JsonNode data = objectMapper.valueToTree(envelope.data());
        if (data == null || !data.path("leaveRequestId").canConvertToLong()
                || data.path("leaveRequestId").asLong() != leaveRequestId) {
            throw new IllegalArgumentException("reply " + envelope.eventId() + " has no leaveRequestId or one that "
                    + "differs from its aggregateId " + leaveRequestId);
        }
    }

    private String reasonOf(EventEnvelope envelope) {
        JsonNode reason = objectMapper.valueToTree(envelope.data()).path("reason");
        return reason.isTextual() && !reason.asText().isBlank() ? reason.asText() : DEFAULT_REFUSAL_REASON;
    }

    private LeaveRequestSnapshot snapshot(LeaveRequest request) {
        return request.snapshot(types.findById(request.leaveTypeId()).orElseThrow().leaveTypeName());
    }
}
