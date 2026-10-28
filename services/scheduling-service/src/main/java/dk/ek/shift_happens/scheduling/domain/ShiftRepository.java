package dk.ek.shift_happens.scheduling.domain;

/** Port for storing shifts. The adapter lives in infrastructure/persistence. */
public interface ShiftRepository {

    Shift save(Shift shift);
}
