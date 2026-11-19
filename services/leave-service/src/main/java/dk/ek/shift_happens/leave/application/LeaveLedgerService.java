package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.leave.domain.InsufficientLeaveBalanceException;
import dk.ek.shift_happens.leave.domain.InvalidLeaveRequestException;
import dk.ek.shift_happens.leave.domain.LeaveBalance;
import dk.ek.shift_happens.leave.domain.LeaveLedgerRepository;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.LeaveRequestRejectedException;
import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import dk.ek.shift_happens.leave.domain.LeaveTypeRepository;
import dk.ek.shift_happens.leave.domain.LedgerEntry;
import dk.ek.shift_happens.leave.domain.TransactionType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * Appends to the immutable leave ledger. Every append and its {@code leave.ledger.entry-recorded.v1} event
 * commit in one transaction (outbox), and every append holds the employee's advisory lock before it reads the
 * balance, so two appends of one employee cannot both build on the same previous entry (duplicate numbers, lost
 * updates, a balance that goes negative). The lock order stays idempotency key, request row, employee.
 *
 * <p>{@link #reserve} and {@link #reverseReservation} are the two steps of the leave-approval saga (#47). A
 * request has one reservation and one reversal per saga attempt: a late completed reply after a timeout
 * compensation reserves again as the next attempt.
 */
@Service
@Profile("command")
public class LeaveLedgerService {

    private final LeaveLedgerRepository ledger;
    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;
    private final EmployeeReplicaRepository employees;
    private final DomainEvents events;
    private final Clock clock;

    LeaveLedgerService(LeaveLedgerRepository ledger, LeaveRequestRepository requests, LeaveTypeRepository types,
                       EmployeeReplicaRepository employees, DomainEvents events, Clock clock) {
        this.ledger = ledger;
        this.requests = requests;
        this.types = types;
        this.employees = employees;
        this.events = events;
        this.clock = clock;
    }

    /** POST /v1/leaveledgers: a hand-posted ACCRUAL or ADJUSTMENT. */
    @Transactional
    public LedgerEntry recordManual(long employeeId, long leaveTypeId, TransactionType type, BigDecimal amount,
                                    String referenceEntityType, Long referenceEntityId, String correlationId) {
        LedgerEntry.requireManual(type);
        LedgerEntry.requireValidAmount(type, amount);
        types.findActiveById(leaveTypeId) // a deleted type counts as unknown
                .orElseThrow(() -> new InvalidLeaveRequestException("unknown leaveTypeId " + leaveTypeId));
        LeaveRequestService.requireActiveEmployee(employees, employeeId, "employee");
        return append(employeeId, leaveTypeId, type, amount, referenceEntityType, referenceEntityId, correlationId);
    }

    /**
     * Saga step 1: reserves the days of the request, which needs a balance of at least that many days (else
     * 409 "Insufficient leave balance"). It locks the request row itself (re-entrant when the caller already holds
     * it) and then the employee, and reads the ledger only after both locks. Both locks are re-entrant: a caller may
     * already hold them, but only if it took them in this order (request row, then employee), as
     * {@code LeaveApprovalSagaService.retry} does. A saga attempt is reserved only once. A refused
     * reservation does not mark the caller's transaction rollback-only, so the caller can catch the exception and
     * go on. {@code causationId} is the eventId of the event that caused this step, or null.
     */
    @Transactional(noRollbackFor = {InsufficientLeaveBalanceException.class, LeaveRequestRejectedException.class})
    public LedgerEntry reserve(long leaveRequestId, int sagaAttempt, String correlationId, UUID causationId) {
        LeaveRequest request = requests.findByIdForUpdate(leaveRequestId)
                .orElseThrow(() -> new LeaveRequestNotFoundException(leaveRequestId));
        requests.lockEmployeeCalendar(request.employeeId()); // before the balance is read (see class comment)
        if (ledger.findForLeaveRequest(leaveRequestId, TransactionType.RESERVATION, sagaAttempt).isPresent()) {
            throw new LeaveRequestRejectedException("leave request " + leaveRequestId + " already has a reservation "
                    + "for saga attempt " + sagaAttempt);
        }
        return appendLocked(request.employeeId(), request.leaveTypeId(), TransactionType.RESERVATION,
                BigDecimal.valueOf(request.days()).negate(), LedgerEntry.LEAVE_REQUEST, leaveRequestId,
                correlationId, causationId, sagaAttempt);
    }

    /**
     * Saga compensation: appends a REVERSAL that undoes the RESERVATION of one saga attempt. It locks the request row and
     * then the employee itself (both locks are re-entrant: a caller may already hold them, but only if it took them in
     * this order, request row then employee) and looks the reservation up
     * only after both locks, so it sees a reservation another transaction has just committed. Idempotent: it
     * returns empty and appends nothing when the request is unknown, the attempt was never reserved or is already
     * reversed.
     */
    @Transactional
    public Optional<LedgerEntry> reverseReservation(long leaveRequestId, int sagaAttempt, String correlationId,
                                                    UUID causationId) {
        Optional<LeaveRequest> request = requests.findByIdForUpdate(leaveRequestId);
        if (request.isEmpty()) {
            return Optional.empty();
        }
        requests.lockEmployeeCalendar(request.get().employeeId());
        Optional<LedgerEntry> reservation = ledger.findForLeaveRequest(leaveRequestId, TransactionType.RESERVATION,
                sagaAttempt);
        if (reservation.isEmpty()
                || ledger.findForLeaveRequest(leaveRequestId, TransactionType.REVERSAL, sagaAttempt).isPresent()) {
            return Optional.empty();
        }
        LedgerEntry reserved = reservation.get();
        return Optional.of(appendLocked(reserved.employeeId(), reserved.leaveTypeId(), TransactionType.REVERSAL,
                reserved.changeAmountDays().negate(), LedgerEntry.LEAVE_REQUEST, leaveRequestId, correlationId,
                causationId, sagaAttempt));
    }

    private LedgerEntry append(long employeeId, long leaveTypeId, TransactionType type, BigDecimal amount,
                               String referenceEntityType, Long referenceEntityId, String correlationId) {
        requests.lockEmployeeCalendar(employeeId);
        return appendLocked(employeeId, leaveTypeId, type, amount, referenceEntityType, referenceEntityId,
                correlationId, null, null);
    }

    /** The employee lock is already held: read the balance, append, snapshot when due, publish. */
    private LedgerEntry appendLocked(long employeeId, long leaveTypeId, TransactionType type, BigDecimal amount,
                                     String referenceEntityType, Long referenceEntityId, String correlationId,
                                     UUID causationId, Integer sagaAttempt) {
        LeaveBalance balance = balance(employeeId, leaveTypeId);
        LedgerEntry next = LedgerEntry.next(employeeId, leaveTypeId, balance, type, amount, referenceEntityType,
                referenceEntityId, clock.instant());
        LedgerEntry saved = ledger.append(next, sagaAttempt);
        if (saved.shouldSnapshot()) {
            ledger.saveSnapshot(saved.snapshot(clock.instant()));
        }
        events.append(DomainEvents.LEAVE_LEDGER_ENTRY_RECORDED, saved.leaveLedgerId(), 1, correlationId,
                causationId, saved.recorded());
        return saved;
    }

    /** The newest snapshot plus the (at most 49) entries after it; never the whole ledger. */
    private LeaveBalance balance(long employeeId, long leaveTypeId) {
        var snapshot = ledger.findNewestSnapshot(employeeId, leaveTypeId);
        long after = snapshot.map(s -> s.entryNumber()).orElse(0L);
        return LeaveBalance.of(snapshot, ledger.findEntriesAfter(employeeId, leaveTypeId, after));
    }
}
