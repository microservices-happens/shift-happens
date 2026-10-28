package dk.ek.shift_happens.scheduling.domain;

import java.util.Optional;

/** Port for storing shifts. The adapter lives in infrastructure/persistence. */
public interface ShiftRepository {

    Shift save(Shift shift);

    /** Includes soft-deleted shifts, so the caller decides what to do with a tombstone. */
    Optional<Shift> findById(long shiftId);

    /** Resolves the shift that owns a required job role row (PUT/DELETE /v1/shiftrequiredjobroles/{id}). */
    Optional<Shift> findByRequiredJobRoleId(long shiftRequiredJobRoleId);
}
