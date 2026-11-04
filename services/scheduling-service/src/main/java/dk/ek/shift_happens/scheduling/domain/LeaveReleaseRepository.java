package dk.ek.shift_happens.scheduling.domain;

import java.util.Optional;

/** Port for the saga replies. The adapter lives in infrastructure/persistence. */
public interface LeaveReleaseRepository {

    Optional<LeaveReleaseRecord> findById(long leaveRequestId);

    LeaveReleaseRecord save(LeaveReleaseRecord release);
}
