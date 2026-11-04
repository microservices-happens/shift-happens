package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.LeaveReleaseRecord;
import dk.ek.shift_happens.scheduling.domain.LeaveReleaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** JPA mapping for leave_release (V6__leave_release.sql): the saga answer per leave request. */
@Entity
@Table(name = "leave_release")
public class LeaveReleaseEntity {

    @Id
    @Column(name = "leave_request_id")
    private long leaveRequestId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "result", nullable = false, length = 20)
    private String result;

    @Convert(converter = LongListConverter.class)
    @Column(name = "released_assignment_ids", columnDefinition = "text")
    private List<Long> releasedAssignmentIds;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LeaveReleaseEntity() {
        // for JPA
    }

    static LeaveReleaseEntity from(LeaveReleaseRecord release) {
        LeaveReleaseEntity entity = new LeaveReleaseEntity();
        entity.leaveRequestId = release.leaveRequestId();
        entity.employeeId = release.employeeId();
        entity.startDate = release.startDate();
        entity.endDate = release.endDate();
        entity.result = release.result().name();
        entity.releasedAssignmentIds = release.releasedAssignmentIds();
        entity.reason = release.reason();
        entity.createdAt = release.createdAt();
        return entity;
    }

    LeaveReleaseRecord toDomain() {
        return new LeaveReleaseRecord(leaveRequestId, employeeId, startDate, endDate,
                LeaveReleaseStatus.valueOf(result),
                releasedAssignmentIds == null ? List.of() : releasedAssignmentIds, reason, createdAt);
    }
}
