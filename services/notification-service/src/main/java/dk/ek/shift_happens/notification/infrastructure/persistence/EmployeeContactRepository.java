package dk.ek.shift_happens.notification.infrastructure.persistence;


import dk.ek.shift_happens.notification.domain.EmployeeContact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Replica of the employee fields this service needs, built from {@code workforce.employee.*} events.
 *
 * <p>Commutative by {@code aggregateVersion}: the highest version wins and a tombstone wins over a lower
 * version, so a redelivered or out-of-order event cannot resurrect a deleted employee
 * (contracts/README.md, "Commutative consumers"). The snapshot row stays after a tombstone; it is the
 * tombstone that keeps the employee out.
 */
@Component
public class EmployeeContactRepository {

    private static final RowMapper<EmployeeContact> MAPPER = (rs, row) -> new EmployeeContact(
            rs.getLong("employee_id"), rs.getString("email"), rs.getString("user_role"),
            rs.getString("employment_status"), rs.getBoolean("deleted"));

    private final JdbcTemplate jdbc;

    public EmployeeContactRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeContact> find(long employeeId) {
        return jdbc.query("SELECT * FROM employee_contact WHERE employee_id=?", MAPPER, employeeId).stream().findFirst();
    }

    public List<EmployeeContact> activeManagers() {
        return jdbc.query("SELECT * FROM employee_contact WHERE user_role='Manager' AND employment_status='ACTIVE' "
                + "AND deleted=FALSE AND email IS NOT NULL ORDER BY employee_id", MAPPER);
    }

    /** Highest version wins; an equal version may overwrite (the snapshot is the same state). */
    public void upsert(EmployeeContact contact, long version) {
        jdbc.update("""
                INSERT INTO employee_contact (employee_id,email,user_role,employment_status,aggregate_version,deleted)
                VALUES (?,?,?,?,?,FALSE)
                ON CONFLICT (employee_id) DO UPDATE
                SET email=EXCLUDED.email, user_role=EXCLUDED.user_role,
                    employment_status=EXCLUDED.employment_status,
                    aggregate_version=EXCLUDED.aggregate_version, deleted=FALSE
                WHERE EXCLUDED.aggregate_version >= employee_contact.aggregate_version
                """, contact.employeeId(), contact.email(), contact.userRole(), contact.employmentStatus(), version);
    }

    /** Keeps the tombstone; a lower-version update that arrives later changes nothing. */
    public void markDeleted(long employeeId, long version) {
        jdbc.update("""
                INSERT INTO employee_contact (employee_id,email,user_role,employment_status,aggregate_version,deleted)
                VALUES (?,'','', 'INACTIVE',?,TRUE)
                ON CONFLICT (employee_id) DO UPDATE
                SET deleted=TRUE, aggregate_version=EXCLUDED.aggregate_version
                WHERE EXCLUDED.aggregate_version >= employee_contact.aggregate_version
                """, employeeId, version);
    }
}
