package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveDecision;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.LeaveRequestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA mapping for leave_request (V3__leave_domain.sql). The decision lives in leave_approval, so the
 * domain object is rebuilt with it by the adapter.
 */
@Entity
@Table(name = "leave_request")
public class LeaveRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_request_id")
    private Long leaveRequestId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "leave_type_id", nullable = false)
    private long leaveTypeId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "request_status", nullable = false, length = 20)
    private String requestStatus;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "requested_datetime", nullable = false)
    private Instant requestedDatetime;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    protected LeaveRequestEntity() {
        // for JPA
    }

    static LeaveRequestEntity from(LeaveRequest request) {
        LeaveRequestEntity entity = new LeaveRequestEntity();
        entity.leaveRequestId = request.leaveRequestId();
        entity.employeeId = request.employeeId();
        entity.leaveTypeId = request.leaveTypeId();
        entity.startDate = request.startDate();
        entity.endDate = request.endDate();
        entity.requestStatus = request.status().name();
        entity.reason = request.reason();
        entity.rejectionReason = request.rejectionReason();
        entity.requestedDatetime = request.requestedDatetime();
        entity.aggregateVersion = request.aggregateVersion();
        return entity;
    }

    Long getLeaveRequestId() {
        return leaveRequestId;
    }

    LeaveRequest toDomain(LeaveDecision decision) {
        return new LeaveRequest(leaveRequestId, employeeId, leaveTypeId, startDate, endDate,
                LeaveRequestStatus.valueOf(requestStatus), reason, requestedDatetime, decision, rejectionReason,
                aggregateVersion);
    }
}
