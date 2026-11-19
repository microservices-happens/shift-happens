package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveType;
import dk.ek.shift_happens.leave.domain.LeaveTypeRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: leave types on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaLeaveTypeRepository implements LeaveTypeRepository {

    private final LeaveTypeJpaRepository jpa;

    JpaLeaveTypeRepository(LeaveTypeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public LeaveType save(LeaveType leaveType) {
        return jpa.save(LeaveTypeEntity.from(leaveType)).toDomain();
    }

    @Override
    public Optional<LeaveType> findById(long leaveTypeId) {
        return jpa.findById(leaveTypeId).map(LeaveTypeEntity::toDomain);
    }

    @Override
    public boolean existsByNameExcluding(String leaveTypeName, Long excludedLeaveTypeId) {
        return jpa.existsByNameExcluding(leaveTypeName, excludedLeaveTypeId);
    }

    @Override
    public void delete(long leaveTypeId) {
        jpa.deleteById(leaveTypeId);
    }
}
