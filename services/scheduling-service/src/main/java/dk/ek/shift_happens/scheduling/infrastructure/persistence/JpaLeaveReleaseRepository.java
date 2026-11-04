package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.LeaveReleaseRecord;
import dk.ek.shift_happens.scheduling.domain.LeaveReleaseRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: the saga replies on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaLeaveReleaseRepository implements LeaveReleaseRepository {

    private final LeaveReleaseJpaRepository jpa;

    JpaLeaveReleaseRepository(LeaveReleaseJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<LeaveReleaseRecord> findById(long leaveRequestId) {
        return jpa.findById(leaveRequestId).map(LeaveReleaseEntity::toDomain);
    }

    @Override
    public LeaveReleaseRecord save(LeaveReleaseRecord release) {
        return jpa.save(LeaveReleaseEntity.from(release)).toDomain();
    }
}
