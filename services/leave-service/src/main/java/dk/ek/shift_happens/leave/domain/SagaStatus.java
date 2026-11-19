package dk.ek.shift_happens.leave.domain;

/** Status of one attempt of the leave-approval saga (V5__leave_approval_saga.sql). */
public enum SagaStatus {
    /** approval-started is published and Scheduling has not answered yet. */
    STARTED,
    /** Scheduling released the employee; the request is APPROVED. */
    COMPLETED,
    /** Scheduling refused the release; the reservation was reversed. */
    COMPENSATED,
    /** No answer before the deadline; the reservation was reversed. A late completed reply can retry. */
    TIMED_OUT
}
