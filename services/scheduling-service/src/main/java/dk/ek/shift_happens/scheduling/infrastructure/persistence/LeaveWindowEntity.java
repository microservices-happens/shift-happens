package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** JPA mapping for leave_window (V7__understaffing_inputs.sql). */
@Entity
@Table(name = "leave_window")
public class LeaveWindowEntity {

    @Id
    @Column(name = "leave_request_id")
    private long leaveRequestId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaveWindowEntity() {
        // for JPA
    }

    static LeaveWindowEntity from(dk.ek.shift_happens.scheduling.domain.LeaveWindow window) {
        LeaveWindowEntity entity = new LeaveWindowEntity();
        entity.leaveRequestId = window.leaveRequestId();
        entity.employeeId = window.employeeId();
        entity.startDate = window.startDate();
        entity.endDate = window.endDate();
        entity.status = window.status().name();
        entity.aggregateVersion = window.aggregateVersion();
        entity.updatedAt = Instant.now();
        return entity;
    }

    dk.ek.shift_happens.scheduling.domain.LeaveWindow toDomain() {
        return new dk.ek.shift_happens.scheduling.domain.LeaveWindow(leaveRequestId, employeeId, startDate,
                endDate, dk.ek.shift_happens.scheduling.domain.LeaveWindowStatus.valueOf(status),
                aggregateVersion);
    }
}
