package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.EmployeeReplica;
import dk.ek.shift_happens.leave.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.leave.domain.InvalidLeaveRequestException;
import dk.ek.shift_happens.leave.domain.LeaveApprovalSaga;
import dk.ek.shift_happens.leave.domain.LeaveApprovalSagaRepository;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.LeaveRequestRejectedException;
import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import dk.ek.shift_happens.leave.domain.LeaveRequestStatus;
import dk.ek.shift_happens.leave.domain.LeaveType;
import dk.ek.shift_happens.leave.domain.LeaveTypeRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Submitting, editing and cancelling leave requests. Each change and its event commit in one
 * transaction (outbox); the rules themselves live in {@link LeaveRequest}.
 */
@Service
@Profile("command")
public class LeaveRequestService {

    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;
    private final EmployeeReplicaRepository employees;
    private final DomainEvents events;
    private final LeaveLedgerService ledger;
    private final LeaveApprovalSagaRepository sagas;
    private final Clock clock;

    LeaveRequestService(LeaveRequestRepository requests, LeaveTypeRepository types,
                        EmployeeReplicaRepository employees, DomainEvents events, LeaveLedgerService ledger,
                        LeaveApprovalSagaRepository sagas, Clock clock) {
        this.requests = requests;
        this.types = types;
        this.employees = employees;
        this.events = events;
        this.ledger = ledger;
        this.sagas = sagas;
        this.clock = clock;
    }

    @Transactional
    public LeaveRequest submit(long employeeId, long leaveTypeId, LocalDate startDate, LocalDate endDate,
                               String reason, String correlationId) {
        LeaveRequest request = LeaveRequest.submit(employeeId, leaveTypeId, startDate, endDate, reason,
                clock.instant());
        LeaveType type = leaveType(leaveTypeId);
        requireActiveEmployee(employeeId, "employee");
        requests.lockEmployeeCalendar(employeeId);
        request.requireNoOverlapWith(requests.findOccupyingByEmployeeId(employeeId));

        LeaveRequest saved = requests.save(request);
        events.append(DomainEvents.LEAVE_REQUEST_SUBMITTED, saved.leaveRequestId(), saved.aggregateVersion(),
                correlationId, saved.snapshot(type.leaveTypeName()));
        return saved;
    }

    /** PATCH: a null argument keeps the current value. The edit is announced with a fresh submitted snapshot. */
    @Transactional
    public LeaveRequest edit(long leaveRequestId, Long leaveTypeId, LocalDate startDate, LocalDate endDate,
                             String reason, String correlationId) {
        LeaveRequest current = findForUpdate(leaveRequestId);
        LeaveRequest edited = current.edited(leaveTypeId, startDate, endDate, reason);
        if (edited.equals(current)) {
            return current; // nothing changed: no version bump, no event
        }
        LeaveType type = leaveType(edited.leaveTypeId());
        requests.lockEmployeeCalendar(current.employeeId());
        edited.requireNoOverlapWith(requests.findOccupyingByEmployeeId(edited.employeeId()));

        LeaveRequest saved = requests.save(edited);
        events.append(DomainEvents.LEAVE_REQUEST_SUBMITTED, saved.leaveRequestId(), saved.aggregateVersion(),
                correlationId, saved.snapshot(type.leaveTypeName()));
        return saved;
    }

    /**
     * DELETE: PENDING to CANCELLED; an already CANCELLED request is an idempotent no-op (no change, no event).
     * An APPROVED request is cancelled too: its reservation is reversed (REVERSAL ledger entry of the completed
     * saga attempt) and leave.request.cancelled.v1 tells Scheduling to drop the leave window. APPROVAL_PENDING is a
     * 409: the saga is in flight. The request row lock is held first; the ledger then takes the employee lock.
     */
    @Transactional
    public LeaveRequest cancel(long leaveRequestId, String correlationId) {
        LeaveRequest current = findForUpdate(leaveRequestId);
        LeaveRequest cancelled = current.cancelled();
        if (cancelled.equals(current)) {
            return current;
        }
        LeaveRequest saved = requests.save(cancelled);
        if (current.status() == LeaveRequestStatus.APPROVED) {
            LeaveApprovalSaga completed = sagas.findCompleted(leaveRequestId).orElseThrow(() ->
                    new IllegalStateException("approved leave request " + leaveRequestId + " has no completed saga"));
            ledger.reverseReservation(leaveRequestId, completed.attempt(), correlationId, null);
        }
        events.append(DomainEvents.LEAVE_REQUEST_CANCELLED, saved.leaveRequestId(), saved.aggregateVersion(),
                correlationId, saved.snapshot(leaveType(saved.leaveTypeId()).leaveTypeName()));
        return saved;
    }

    private LeaveRequest findForUpdate(long leaveRequestId) {
        return requests.findByIdForUpdate(leaveRequestId).orElseThrow(() -> new LeaveRequestNotFoundException(leaveRequestId));
    }

    private LeaveType leaveType(long leaveTypeId) {
        return types.findById(leaveTypeId)
                .orElseThrow(() -> new InvalidLeaveRequestException("unknown leaveTypeId " + leaveTypeId));
    }

    /**
     * The replica is eventually consistent, so "unknown" can also mean "not replicated yet"; the detail says so
     * and the client may retry.
     */
    static void requireActiveEmployee(EmployeeReplicaRepository employees, long employeeId, String role) {
        boolean active = employees.findByEmployeeId(employeeId).filter(EmployeeReplica::isActive).isPresent();
        if (!active) {
            throw new LeaveRequestRejectedException(role + " " + employeeId + " is unknown, inactive or deleted in "
                    + "the local employee replica (the replica may lag behind workforce; retry shortly)");
        }
    }

    private void requireActiveEmployee(long employeeId, String role) {
        requireActiveEmployee(employees, employeeId, role);
    }
}
