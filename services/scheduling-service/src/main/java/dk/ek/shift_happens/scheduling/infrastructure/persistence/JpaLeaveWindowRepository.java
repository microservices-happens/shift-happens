package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.LeaveWindow;
import dk.ek.shift_happens.scheduling.domain.LeaveWindowRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Adapter: the copied leave windows on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaLeaveWindowRepository implements LeaveWindowRepository {

    private final LeaveWindowJpaRepository jpa;

    JpaLeaveWindowRepository(LeaveWindowJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<LeaveWindow> findById(long leaveRequestId) {
        return jpa.findById(leaveRequestId).map(LeaveWindowEntity::toDomain);
    }

    @Override
    public LeaveWindow save(LeaveWindow window) {
        return jpa.save(LeaveWindowEntity.from(window)).toDomain();
    }

    @Override
    public List<LeaveWindow> findApprovedOverlapping(long employeeId, LocalDate from, LocalDate to) {
        return jpa.findApprovedOverlapping(employeeId, from, to).stream()
                .map(LeaveWindowEntity::toDomain)
                .toList();
    }
}
