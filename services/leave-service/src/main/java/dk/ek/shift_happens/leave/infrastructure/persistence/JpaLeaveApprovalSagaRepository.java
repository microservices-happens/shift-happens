package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveApprovalSaga;
import dk.ek.shift_happens.leave.domain.LeaveApprovalSagaRepository;
import dk.ek.shift_happens.leave.domain.SagaStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapter: saga attempts on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaLeaveApprovalSagaRepository implements LeaveApprovalSagaRepository {

    private final LeaveApprovalSagaJpaRepository jpa;

    JpaLeaveApprovalSagaRepository(LeaveApprovalSagaJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** Flushes so a violated unique index (a second STARTED attempt) surfaces inside the transaction. */
    @Override
    public LeaveApprovalSaga save(LeaveApprovalSaga saga) {
        return jpa.saveAndFlush(LeaveApprovalSagaEntity.from(saga)).toDomain();
    }

    @Override
    public Optional<LeaveApprovalSaga> findById(long sagaId) {
        return jpa.findById(sagaId).map(LeaveApprovalSagaEntity::toDomain);
    }

    @Override
    public Optional<LeaveApprovalSaga> findByApprovalStartedEventId(UUID eventId) {
        return jpa.findByApprovalStartedEventId(eventId).map(LeaveApprovalSagaEntity::toDomain);
    }

    @Override
    public Optional<LeaveApprovalSaga> findLatest(long leaveRequestId) {
        return jpa.findFirstByLeaveRequestIdOrderByAttemptDesc(leaveRequestId)
                .map(LeaveApprovalSagaEntity::toDomain);
    }

    @Override
    public Optional<LeaveApprovalSaga> findStarted(long leaveRequestId) {
        return jpa.findFirstByLeaveRequestIdAndStatus(leaveRequestId, SagaStatus.STARTED.name())
                .map(LeaveApprovalSagaEntity::toDomain);
    }

    @Override
    public Optional<LeaveApprovalSaga> findCompleted(long leaveRequestId) {
        return jpa.findFirstByLeaveRequestIdAndStatus(leaveRequestId, SagaStatus.COMPLETED.name())
                .map(LeaveApprovalSagaEntity::toDomain);
    }

    @Override
    public List<LeaveApprovalSaga> findOverdue(Instant now, int limit) {
        return jpa.findOverdue(now, PageRequest.of(0, limit)).stream().map(LeaveApprovalSagaEntity::toDomain)
                .toList();
    }
}
