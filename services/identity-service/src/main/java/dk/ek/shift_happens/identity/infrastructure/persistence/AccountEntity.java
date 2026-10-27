package dk.ek.shift_happens.identity.infrastructure.persistence;

import dk.ek.shift_happens.identity.domain.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for the account table (V1__init.sql). */
@Entity
@Table(name = "account")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "employee_id", nullable = false, unique = true)
    private long employeeId;

    @Column(name = "employee_number", length = 50)
    private String employeeNumber;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountEntity() {
        // for JPA
    }

    void apply(Account account) {
        this.employeeId = account.employeeId();
        this.employeeNumber = account.employeeNumber();
        this.email = account.email();
        this.role = account.role();
        this.active = account.active();
        this.aggregateVersion = account.aggregateVersion();
        this.updatedAt = Instant.now();
    }

    Account toDomain() {
        return new Account(accountId, employeeId, employeeNumber, email, role, active, aggregateVersion);
    }
}
