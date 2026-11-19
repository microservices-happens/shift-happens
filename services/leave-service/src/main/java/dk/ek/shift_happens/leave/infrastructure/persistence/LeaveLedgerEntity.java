package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LedgerEntry;
import dk.ek.shift_happens.leave.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

/** JPA mapping for leave_ledger (V4__leave_ledger.sql). Immutable: the table refuses UPDATE and DELETE. */
@Entity
@Immutable
@Table(name = "leave_ledger")
public class LeaveLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_ledger_id")
    private Long leaveLedgerId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "leave_type_id", nullable = false)
    private long leaveTypeId;

    @Column(name = "entry_number", nullable = false)
    private long entryNumber;

    @Column(name = "change_amount_days", nullable = false, precision = 7, scale = 2)
    private BigDecimal changeAmountDays;

    @Column(name = "balance_after_days", nullable = false, precision = 9, scale = 2)
    private BigDecimal balanceAfterDays;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;

    @Column(name = "reference_entity_type", length = 50)
    private String referenceEntityType;

    @Column(name = "reference_entity_id")
    private Long referenceEntityId;

    @Column(name = "transaction_datetime", nullable = false)
    private Instant transactionDatetime;

    /** The leave-approval saga attempt of a RESERVATION or REVERSAL (V5), else null. */
    @Column(name = "saga_attempt")
    private Integer sagaAttempt;

    protected LeaveLedgerEntity() {
        // for JPA
    }

    static LeaveLedgerEntity from(LedgerEntry entry, Integer sagaAttempt) {
        LeaveLedgerEntity entity = new LeaveLedgerEntity();
        entity.leaveLedgerId = entry.leaveLedgerId();
        entity.employeeId = entry.employeeId();
        entity.leaveTypeId = entry.leaveTypeId();
        entity.entryNumber = entry.entryNumber();
        entity.changeAmountDays = entry.changeAmountDays();
        entity.balanceAfterDays = entry.balanceAfterDays();
        entity.transactionType = entry.transactionType().name();
        entity.referenceEntityType = entry.referenceEntityType();
        entity.referenceEntityId = entry.referenceEntityId();
        entity.transactionDatetime = entry.transactionDatetime();
        entity.sagaAttempt = sagaAttempt;
        return entity;
    }

    LedgerEntry toDomain() {
        return new LedgerEntry(leaveLedgerId, employeeId, leaveTypeId, entryNumber, changeAmountDays,
                balanceAfterDays, TransactionType.valueOf(transactionType), referenceEntityType,
                referenceEntityId, transactionDatetime);
    }
}
