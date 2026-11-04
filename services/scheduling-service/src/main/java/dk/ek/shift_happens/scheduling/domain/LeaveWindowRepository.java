package dk.ek.shift_happens.scheduling.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Port for the leave windows copied from leave.* events. The adapter lives in infrastructure. */
public interface LeaveWindowRepository {

    Optional<LeaveWindow> findById(long leaveRequestId);

    LeaveWindow save(LeaveWindow window);

    /** Approved windows of that employee that overlap [from, to]. */
    List<LeaveWindow> findApprovedOverlapping(long employeeId, LocalDate from, LocalDate to);
}
