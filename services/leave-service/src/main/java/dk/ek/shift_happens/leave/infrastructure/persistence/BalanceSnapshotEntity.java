package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.BalanceSnapshot;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/** JPA mapping for balance_snapshot (V4__leave_ledger.sql). Immutable, like the ledger it summarizes. */
@Entity
@Immutable
@Table(name = "balance_snapshot")
@IdClass(BalanceSnapshotEntity.Key.class)
public class BalanceSnapshotEntity {

    /** Composite primary key (employee_id, leave_type_id, entry_number). */
    public record Key(long employeeId, long leaveTypeId, long entryNumber) implements Serializable {
    }

    @Id
    @Column(name = "employee_id")
    private long employeeId;

    @Id
    @Column(name = "leave_type_id")
    private long leaveTypeId;

    @Id
    @Column(name = "entry_number")
    private long entryNumber;

    @Column(name = "balance_days", nullable = false, precision = 9, scale = 2)
    private BigDecimal balanceDays;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BalanceSnapshotEntity() {
        // for JPA
    }

    static BalanceSnapshotEntity from(BalanceSnapshot snapshot) {
        BalanceSnapshotEntity entity = new BalanceSnapshotEntity();
        entity.employeeId = snapshot.employeeId();
        entity.leaveTypeId = snapshot.leaveTypeId();
        entity.entryNumber = snapshot.entryNumber();
        entity.balanceDays = snapshot.balanceDays();
        entity.createdAt = snapshot.createdAt();
        return entity;
    }

    BalanceSnapshot toDomain() {
        return new BalanceSnapshot(employeeId, leaveTypeId, entryNumber, balanceDays, createdAt);
    }
}
