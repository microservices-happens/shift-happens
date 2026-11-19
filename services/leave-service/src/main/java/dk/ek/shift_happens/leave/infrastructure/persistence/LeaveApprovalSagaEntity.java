package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveApprovalSaga;
import dk.ek.shift_happens.leave.domain.SagaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping for leave_approval_saga (V5__leave_approval_saga.sql). */
@Entity
@Table(name = "leave_approval_saga")
public class LeaveApprovalSagaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_approval_saga_id")
    private Long sagaId;

    @Column(name = "leave_request_id", nullable = false)
    private long leaveRequestId;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "saga_status", nullable = false, length = 20)
    private String status;

    @Column(name = "approval_started_event_id", nullable = false)
    private UUID approvalStartedEventId;

    @Column(name = "correlation_id", nullable = false, columnDefinition = "text")
    private String correlationId;

    @Column(name = "deadline", nullable = false)
    private Instant deadline;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaveApprovalSagaEntity() {
        // for JPA
    }

    static LeaveApprovalSagaEntity from(LeaveApprovalSaga saga) {
        LeaveApprovalSagaEntity entity = new LeaveApprovalSagaEntity();
        entity.sagaId = saga.sagaId();
        entity.leaveRequestId = saga.leaveRequestId();
        entity.attempt = saga.attempt();
        entity.status = saga.status().name();
        entity.approvalStartedEventId = saga.approvalStartedEventId();
        entity.correlationId = saga.correlationId();
        entity.deadline = saga.deadline();
        entity.createdAt = saga.createdAt();
        entity.updatedAt = saga.updatedAt();
        return entity;
    }

    LeaveApprovalSaga toDomain() {
        return new LeaveApprovalSaga(sagaId, leaveRequestId, attempt, SagaStatus.valueOf(status),
                approvalStartedEventId, correlationId, deadline, createdAt, updatedAt);
    }
}
