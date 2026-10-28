package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Adapter: implements the domain port on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaShiftRepository implements ShiftRepository {

    private final ShiftJpaRepository jpa;

    JpaShiftRepository(ShiftJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Shift save(Shift shift) {
        return jpa.save(ShiftEntity.from(shift)).toDomain();
    }
}
