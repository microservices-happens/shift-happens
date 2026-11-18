package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.EmployeeReplicaRepository;
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

/**
 * Appends to the immutable leave ledger. Every append and its {@code leave.ledger.entry-recorded.v1} event
 * commit in one transaction (outbox), and every append holds the employee's advisory lock before it reads the
 * balance, so two appends of one employee cannot both build on the same previous entry (duplicate numbers, lost
 * updates, a balance that goes negative). The lock order stays idempotency key, request row, employee.
 *
 * <p>{@link #reserve} and {@link #reverseReservation} are the two steps of the leave-approval saga (#47); no
 * endpoint calls them yet.
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
        types.findById(leaveTypeId)
                .orElseThrow(() -> new InvalidLeaveRequestException("unknown leaveTypeId " + leaveTypeId));
        LeaveRequestService.requireActiveEmployee(employees, employeeId, "employee");
        return append(employeeId, leaveTypeId, type, amount, referenceEntityType, referenceEntityId, correlationId);
    }

    /**
     * Saga step 1: reserves the days of the request, which needs a balance of at least that many days (else
     * 409 "Insufficient leave balance"). The caller holds the request row lock. A request is reserved only once.
     */
    @Transactional
    public LedgerEntry reserve(LeaveRequest request, String correlationId) {
        if (request.leaveRequestId() == null) {
            throw new IllegalArgumentException("only a saved leave request can be reserved");
        }
        long employeeId = request.employeeId();
        long leaveTypeId = request.leaveTypeId();
        requests.lockEmployeeCalendar(employeeId); // before the balance is read (see class comment)
        if (ledger.findForLeaveRequest(request.leaveRequestId(), TransactionType.RESERVATION).isPresent()) {
            throw new LeaveRequestRejectedException("leave request " + request.leaveRequestId()
                    + " already has a reservation");
        }
        return appendLocked(employeeId, leaveTypeId, TransactionType.RESERVATION,
                BigDecimal.valueOf(request.days()).negate(), LedgerEntry.LEAVE_REQUEST, request.leaveRequestId(),
                correlationId);
    }

    /**
     * Saga compensation: appends a REVERSAL that undoes the request's RESERVATION. Idempotent: it returns
     * empty and appends nothing when the request was never reserved or is already reversed.
     */
    @Transactional
    public Optional<LedgerEntry> reverseReservation(long leaveRequestId, String correlationId) {
        // An entry never changes, so reading it before the lock is safe; the "already reversed" check is not.
        Optional<LedgerEntry> reservation = ledger.findForLeaveRequest(leaveRequestId, TransactionType.RESERVATION);
        if (reservation.isEmpty()) {
            return Optional.empty();
        }
        LedgerEntry reserved = reservation.get();
        requests.lockEmployeeCalendar(reserved.employeeId());
        if (ledger.findForLeaveRequest(leaveRequestId, TransactionType.REVERSAL).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(appendLocked(reserved.employeeId(), reserved.leaveTypeId(), TransactionType.REVERSAL,
                reserved.changeAmountDays().negate(), LedgerEntry.LEAVE_REQUEST, leaveRequestId, correlationId));
    }

    private LedgerEntry append(long employeeId, long leaveTypeId, TransactionType type, BigDecimal amount,
                               String referenceEntityType, Long referenceEntityId, String correlationId) {
        requests.lockEmployeeCalendar(employeeId);
        return appendLocked(employeeId, leaveTypeId, type, amount, referenceEntityType, referenceEntityId,
                correlationId);
    }

    /** The employee lock is already held: read the balance, append, snapshot when due, publish. */
    private LedgerEntry appendLocked(long employeeId, long leaveTypeId, TransactionType type, BigDecimal amount,
                                     String referenceEntityType, Long referenceEntityId, String correlationId) {
        LeaveBalance balance = balance(employeeId, leaveTypeId);
        LedgerEntry next = LedgerEntry.next(employeeId, leaveTypeId, balance, type, amount, referenceEntityType,
                referenceEntityId, clock.instant());
        LedgerEntry saved = ledger.append(next);
        if (saved.shouldSnapshot()) {
            ledger.saveSnapshot(saved.snapshot(clock.instant()));
        }
        events.append(DomainEvents.LEAVE_LEDGER_ENTRY_RECORDED, saved.leaveLedgerId(), 1, correlationId,
                saved.recorded());
        return saved;
    }

    /** The newest snapshot plus the (at most 49) entries after it; never the whole ledger. */
    private LeaveBalance balance(long employeeId, long leaveTypeId) {
        var snapshot = ledger.findNewestSnapshot(employeeId, leaveTypeId);
        long after = snapshot.map(s -> s.entryNumber()).orElse(0L);
        return LeaveBalance.of(snapshot, ledger.findEntriesAfter(employeeId, leaveTypeId, after));
    }
}
