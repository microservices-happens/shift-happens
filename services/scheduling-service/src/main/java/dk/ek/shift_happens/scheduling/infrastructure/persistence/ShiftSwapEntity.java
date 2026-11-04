package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ShiftSwap;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA mapping for shift_swap (V5__swaps.sql). The decision lives in shift_swap_approval, so the domain
 * is rebuilt with it by the adapter.
 */
@Entity
@Table(name = "shift_swap")
public class ShiftSwapEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_swap_id")
    private Long shiftSwapId;

    @Column(name = "original_shift_assignment_id", nullable = false)
    private long originalShiftAssignmentId;

    @Column(name = "shift_id", nullable = false)
    private long shiftId;

    @Column(name = "employee_from_id", nullable = false)
    private long employeeFromId;

    @Column(name = "employee_to_id", nullable = false)
    private long employeeToId;

    @Column(name = "swap_status", nullable = false, length = 20)
    private String swapStatus;

    @Column(name = "request_datetime", nullable = false)
    private Instant requestDatetime;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    protected ShiftSwapEntity() {
        // for JPA
    }

    static ShiftSwapEntity from(ShiftSwap swap) {
        ShiftSwapEntity entity = new ShiftSwapEntity();
        entity.shiftSwapId = swap.shiftSwapId();
        entity.originalShiftAssignmentId = swap.originalShiftAssignmentId();
        entity.shiftId = swap.shiftId();
        entity.employeeFromId = swap.employeeFromId();
        entity.employeeToId = swap.employeeToId();
        entity.swapStatus = swap.status().name();
        entity.requestDatetime = swap.requestDatetime();
        entity.reason = swap.reason();
        entity.aggregateVersion = swap.aggregateVersion();
        return entity;
    }

    Long getShiftSwapId() {
        return shiftSwapId;
    }

    ShiftSwap toDomain(dk.ek.shift_happens.scheduling.domain.SwapDecision decision) {
        return new ShiftSwap(shiftSwapId, originalShiftAssignmentId, shiftId, employeeFromId, employeeToId,
                SwapStatus.valueOf(swapStatus), reason, requestDatetime, decision, aggregateVersion);
    }
}
