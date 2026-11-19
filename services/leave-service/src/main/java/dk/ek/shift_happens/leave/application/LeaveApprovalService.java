package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.ApprovalDecision;
import dk.ek.shift_happens.leave.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.leave.domain.LeaveApproval;
import dk.ek.shift_happens.leave.domain.LeaveApprovalRepository;
import dk.ek.shift_happens.leave.domain.LeaveDecision;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import dk.ek.shift_happens.leave.domain.LeaveTypeRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * A manager decides about a leave request. REJECTED is final and publishes leave.request.rejected.v1;
 * APPROVED belongs to the leave-approval saga and is refused until #47 / P3.1 builds it.
 */
@Service
@Profile("command")
public class LeaveApprovalService {

    static final String DEFAULT_REJECTION_REASON = "Rejected by manager";

    private final LeaveApprovalRepository approvals;
    private final LeaveRequestRepository requests;
    private final LeaveTypeRepository types;
    private final EmployeeReplicaRepository employees;
    private final DomainEvents events;
    private final Clock clock;

    LeaveApprovalService(LeaveApprovalRepository approvals, LeaveRequestRepository requests,
                         LeaveTypeRepository types, EmployeeReplicaRepository employees, DomainEvents events,
                         Clock clock) {
        this.approvals = approvals;
        this.requests = requests;
        this.types = types;
        this.employees = employees;
        this.events = events;
        this.clock = clock;
    }

    /** POST /v1/leaveapprovals. An unknown leaveRequestId is a 404, as an unknown swap is in scheduling. */
    @Transactional
    public LeaveApproval decide(long leaveRequestId, long approverEmployeeId, ApprovalDecision decision,
                                String comment, String correlationId) {
        LeaveRequest request = requests.findByIdForUpdate(leaveRequestId)
                .orElseThrow(() -> new LeaveRequestNotFoundException(leaveRequestId));
        // TODO(P2.1): once the JWT is validated, require the Administrator or Manager role here.
        LeaveRequestService.requireActiveEmployee(employees, approverEmployeeId, "approver");

        LeaveDecision verdict = new LeaveDecision(approverEmployeeId, decision, comment, clock.instant());
        return decision == ApprovalDecision.APPROVED
                ? startApprovalSaga(request, verdict)
                : reject(request, verdict, correlationId);
    }

    /** PUT /v1/leaveapprovals/{id}: a stored decision is final, so this is a 404 or a 409 (see LeaveApproval). */
    @Transactional
    public LeaveApproval replace(long leaveApprovalId) {
        LeaveApproval existing = approvals.findById(leaveApprovalId)
                .orElseThrow(() -> new LeaveApprovalNotFoundException(leaveApprovalId));
        existing.requireChangeable();
        return existing;
    }

    /** The one place #47 replaces: today an APPROVED decision is refused without any state change or event. */
    private LeaveApproval startApprovalSaga(LeaveRequest request, LeaveDecision approval) {
        request.startApproval(approval);
        throw new IllegalStateException("unreachable until the leave-approval saga exists (#47)");
    }

    private LeaveApproval reject(LeaveRequest request, LeaveDecision rejection, String correlationId) {
        LeaveRequest rejected = request.rejected(rejection, DEFAULT_REJECTION_REASON);

        LeaveApproval saved = approvals.save(new LeaveApproval(null, request.leaveRequestId(),
                rejection.approverEmployeeId(), rejection.decision(), rejection.comment(),
                rejection.decisionDatetime()));
        // Saved after the approval, so the adapter joins the decision into the snapshot.
        LeaveRequest persisted = requests.save(rejected);
        String typeName = types.findById(persisted.leaveTypeId()).orElseThrow().leaveTypeName();
        events.append(DomainEvents.LEAVE_REQUEST_REJECTED, persisted.leaveRequestId(),
                persisted.aggregateVersion(), correlationId, persisted.snapshot(typeName));
        return saved;
    }
}
