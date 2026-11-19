package dk.ek.shift_happens.leave.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt of the leave-approval saga of a request (contracts/events/catalog.md, "Flow: leave-approval saga").
 * Attempt 1 starts when a manager approves; a late completed reply after a timeout compensation starts the next
 * attempt, at most {@link #MAX_ATTEMPTS} in all. Replies correlate on {@code approvalStartedEventId}, the eventId of
 * the leave.request.approval-started.v1 of this attempt (the reply's causationId). The rules of what a reply does
 * in which status are plain Java here, so they are testable without Spring.
 */
public record LeaveApprovalSaga(
        Long sagaId,
        long leaveRequestId,
        int attempt,
        SagaStatus status,
        UUID approvalStartedEventId,
        String correlationId,
        Instant deadline,
        Instant createdAt,
        Instant updatedAt) {

    /** One initial attempt and two retries after a timeout compensation. */
    public static final int MAX_ATTEMPTS = 3;

    /** The two replies of Scheduling (scheduling.leave-release.completed.v1 / .rejected.v1). */
    public enum Reply { COMPLETED, REJECTED }

    /** What the orchestrator does with a reply. */
    public enum Reaction { COMPLETE, COMPENSATE, RETRY, IGNORE }

    public static LeaveApprovalSaga start(long leaveRequestId, int attempt, UUID approvalStartedEventId,
                                          String correlationId, Instant now, Duration timeout) {
        return new LeaveApprovalSaga(null, leaveRequestId, attempt, SagaStatus.STARTED, approvalStartedEventId,
                correlationId, now.plus(timeout), now, now);
    }

    /**
     * What a reply does. {@code latestAttempt} says whether this is the newest attempt of the request: only the
     * newest attempt can be retried, so a duplicate or very late reply of an old attempt is ignored.
     * <ul>
     *   <li>STARTED: completed completes, rejected compensates;</li>
     *   <li>TIMED_OUT: a late completed retries (newest attempt, below the cap), a late rejected is ignored;</li>
     *   <li>COMPLETED and COMPENSATED are final: every reply is ignored.</li>
     * </ul>
     */
    public Reaction reactTo(Reply reply, boolean latestAttempt) {
        return switch (status) {
            case STARTED -> reply == Reply.COMPLETED ? Reaction.COMPLETE : Reaction.COMPENSATE;
            case TIMED_OUT -> reply == Reply.COMPLETED && latestAttempt && attempt < MAX_ATTEMPTS
                    ? Reaction.RETRY
                    : Reaction.IGNORE;
            case COMPLETED, COMPENSATED -> Reaction.IGNORE;
        };
    }

    public boolean isOverdue(Instant now) {
        return status == SagaStatus.STARTED && deadline.isBefore(now);
    }

    public LeaveApprovalSaga completed(Instant now) {
        return transitionFromStarted(SagaStatus.COMPLETED, now);
    }

    public LeaveApprovalSaga compensated(Instant now) {
        return transitionFromStarted(SagaStatus.COMPENSATED, now);
    }

    public LeaveApprovalSaga timedOut(Instant now) {
        return transitionFromStarted(SagaStatus.TIMED_OUT, now);
    }

    private LeaveApprovalSaga transitionFromStarted(SagaStatus target, Instant now) {
        if (status != SagaStatus.STARTED) {
            throw new IllegalStateException("saga attempt " + attempt + " of leave request " + leaveRequestId
                    + " is " + status + " and cannot become " + target);
        }
        return new LeaveApprovalSaga(sagaId, leaveRequestId, attempt, target, approvalStartedEventId,
                correlationId, deadline, createdAt, now);
    }
}
