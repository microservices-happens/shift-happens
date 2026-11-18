package dk.ek.shift_happens.leave.domain;

/**
 * Approving leave starts the leave-approval saga, which is not built yet (P3.1 / #47). Maps to 409
 * so a client gets a clear answer instead of a half-done approval.
 */
public class ApprovalNotAvailableException extends RuntimeException {

    public ApprovalNotAvailableException() {
        super("Approving leave starts the leave-approval saga, which is not implemented yet (P3.1)");
    }
}
