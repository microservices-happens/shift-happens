package dk.ek.shift_happens.leave.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for the saga attempts of leave requests. Callers hold the request's row lock before they read. */
public interface LeaveApprovalSagaRepository {

    LeaveApprovalSaga save(LeaveApprovalSaga saga);

    Optional<LeaveApprovalSaga> findById(long sagaId);

    /** The attempt whose approval-started event has this eventId (what a reply's causationId points at). */
    Optional<LeaveApprovalSaga> findByApprovalStartedEventId(UUID eventId);

    /** The attempt with the highest number, whatever its status. */
    Optional<LeaveApprovalSaga> findLatest(long leaveRequestId);

    /** The attempt that is in flight, if any (at most one by a unique index). */
    Optional<LeaveApprovalSaga> findStarted(long leaveRequestId);

    /** The attempt that completed, if any (the one whose reservation an APPROVED request holds). */
    Optional<LeaveApprovalSaga> findCompleted(long leaveRequestId);

    /** Attempts in flight whose deadline passed before {@code now}, most overdue first. A plain read, no lock. */
    List<LeaveApprovalSaga> findOverdue(Instant now, int limit);
}
