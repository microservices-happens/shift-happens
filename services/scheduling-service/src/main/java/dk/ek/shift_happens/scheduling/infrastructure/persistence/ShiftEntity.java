package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA mapping for shift and its shift_required_job_role rows (V1__init.sql).
 * The domain type stays framework-free; this class only translates.
 */
@Entity
@Table(name = "shift")
public class ShiftEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_id")
    private Long shiftId;

    @Column(name = "department_id", nullable = false)
    private long departmentId;

    @Column(name = "work_location_id", nullable = false)
    private long workLocationId;

    @Column(name = "shift_name", nullable = false, length = 255)
    private String shiftName;

    @Column(name = "start_datetime", nullable = false)
    private Instant startDatetime;

    @Column(name = "end_datetime", nullable = false)
    private Instant endDatetime;

    @Column(name = "shift_status", nullable = false, length = 20)
    private String shiftStatus;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "shift_id", nullable = false)
    @OrderBy("jobRoleId ASC")
    private List<ShiftRequiredJobRoleEntity> requiredJobRoles = new ArrayList<>();

    protected ShiftEntity() {
        // for JPA
    }

    static ShiftEntity from(Shift shift) {
        ShiftEntity entity = new ShiftEntity();
        entity.shiftId = shift.shiftId();
        entity.departmentId = shift.departmentId();
        entity.workLocationId = shift.workLocationId();
        entity.shiftName = shift.shiftName();
        entity.startDatetime = shift.startDatetime();
        entity.endDatetime = shift.endDatetime();
        entity.shiftStatus = shift.status().name();
        entity.aggregateVersion = shift.aggregateVersion();
        entity.deleted = shift.deleted();
        entity.requiredJobRoles = new ArrayList<>(shift.requiredJobRoles().stream()
                .map(ShiftRequiredJobRoleEntity::from)
                .toList());
        return entity;
    }

    Shift toDomain() {
        return new Shift(shiftId, departmentId, workLocationId, shiftName, startDatetime, endDatetime,
                ShiftStatus.valueOf(shiftStatus),
                requiredJobRoles.stream().map(ShiftRequiredJobRoleEntity::toDomain).toList(),
                aggregateVersion, deleted);
    }
}
