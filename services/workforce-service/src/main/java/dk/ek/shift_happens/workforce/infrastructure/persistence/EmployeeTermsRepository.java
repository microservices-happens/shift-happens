package dk.ek.shift_happens.workforce.infrastructure.persistence;

import dk.ek.shift_happens.workforce.api.dto.EmployeeContract;
import dk.ek.shift_happens.workforce.api.dto.EmployeeJobRole;
import dk.ek.shift_happens.workforce.application.ReferenceDataNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class EmployeeTermsRepository {

    private final JdbcTemplate jdbc;

    EmployeeTermsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Refresh(LocalDate dueOn, LocalDate evaluatedOn) {
    }

    public List<Long> dueEmployeeIds(LocalDate today) {
        return jdbc.queryForList("""
                select employee_id from employee_terms_refresh
                where due_on <= ? order by due_on, employee_id limit 100
                """, Long.class, today);
    }

    public java.util.Optional<Refresh> dueRefresh(long employeeId, LocalDate today) {
        return jdbc.query("""
                select due_on, evaluated_on from employee_terms_refresh where employee_id = ? and due_on <= ?
                """, (row, index) -> new Refresh(row.getObject("due_on", LocalDate.class),
                        row.getObject("evaluated_on", LocalDate.class)), employeeId, today).stream().findFirst();
    }

    public void reschedule(long employeeId, LocalDate evaluatedOn) {
        LocalDate nextDate = jdbc.queryForObject("""
                select min(boundary) from (
                    select start_date as boundary from employee_contract where employee_id = ? and is_active
                    union all
                    select end_date + 1 from employee_contract where employee_id = ? and is_active
                    union all
                    select assigned_date from employee_job_role where employee_id = ?
                    union all
                    select expiry_date + 1 from employee_job_role where employee_id = ?
                ) dates where boundary > ?
                """, LocalDate.class, employeeId, employeeId, employeeId, employeeId, evaluatedOn);
        if (nextDate == null) {
            clearRefresh(employeeId);
            return;
        }
        jdbc.update("""
                insert into employee_terms_refresh (employee_id, due_on, evaluated_on) values (?, ?, ?)
                on conflict (employee_id) do update
                set due_on = excluded.due_on, evaluated_on = excluded.evaluated_on
                """, employeeId, nextDate, evaluatedOn);
    }

    public void clearRefresh(long employeeId) {
        jdbc.update("delete from employee_terms_refresh where employee_id = ?", employeeId);
    }

    public List<Long> activeJobRoleIds(long employeeId, LocalDate today) {
        return jdbc.queryForList("""
                select job_role_id from employee_job_role
                where employee_id = ? and assigned_date <= ?
                  and (expiry_date is null or expiry_date >= ?)
                order by job_role_id
                """, Long.class, employeeId, today, today);
    }

    public int weeklyContractHours(long employeeId, LocalDate today) {
        List<Integer> hours = jdbc.queryForList("""
                select coalesce(weekly_hours, 0) from employee_contract
                where employee_id = ? and is_active and start_date <= ?
                  and (end_date is null or end_date >= ?)
                order by start_date desc, contract_id desc
                limit 1
                """, Integer.class, employeeId, today, today);
        return hours.isEmpty() ? 0 : hours.getFirst();
    }

    private static final RowMapper<EmployeeContract> EMPLOYEE_CONTRACT = (row, index) -> new EmployeeContract(
            row.getLong("contract_id"),
            row.getObject("employee_id", Long.class),
            row.getObject("department_id", Long.class),
            row.getString("contract_type"),
            row.getObject("start_date", LocalDate.class),
            row.getObject("end_date", LocalDate.class),
            row.getObject("weekly_hours", Integer.class),
            row.getObject("is_active", Boolean.class));

    public List<EmployeeContract> listEmployeeContracts() {
        return jdbc.query("""
                select contract_id, employee_id, department_id, contract_type, start_date, end_date, weekly_hours, is_active
                from employee_contract order by contract_id
                """, EMPLOYEE_CONTRACT);
    }

    public EmployeeContract getEmployeeContract(long id) {
        return findEmployeeContract(id, false);
    }

    public EmployeeContract lockEmployeeContract(long id) {
        return findEmployeeContract(id, true);
    }

    private EmployeeContract findEmployeeContract(long id, boolean lock) {
        String sql = """
                select contract_id, employee_id, department_id, contract_type, start_date, end_date, weekly_hours, is_active
                from employee_contract where contract_id = ?
                """;
        return jdbc.query(sql + (lock ? " for update" : ""), EMPLOYEE_CONTRACT, id).stream().findFirst()
                .orElseThrow(() -> new ReferenceDataNotFoundException("Employee Contract", id));
    }

    public EmployeeContract createEmployeeContract(EmployeeContract request) {
        return jdbc.queryForObject("""
                insert into employee_contract (employee_id, department_id, contract_type, start_date, end_date, weekly_hours, is_active)
                values (?, ?, ?, ?, ?, ?, ?)
                returning contract_id, employee_id, department_id, contract_type, start_date, end_date, weekly_hours, is_active
                """, EMPLOYEE_CONTRACT,
                request.employeeId(),
                request.departmentId(),
                request.contractType(),
                request.startDate(),
                request.endDate(),
                request.weeklyHours(),
                !Boolean.FALSE.equals(request.isActive()));
    }

    public EmployeeContract updateEmployeeContract(long id, EmployeeContract request) {
        return jdbc.queryForObject("""
                update employee_contract
                set employee_id = ?,
                    department_id = ?,
                    contract_type = ?,
                    start_date = ?,
                    end_date = ?,
                    weekly_hours = ?,
                    is_active = ?
                where contract_id = ?
                returning contract_id, employee_id, department_id, contract_type, start_date, end_date, weekly_hours, is_active
                """, EMPLOYEE_CONTRACT,
                request.employeeId(),
                request.departmentId(),
                request.contractType(),
                request.startDate(),
                request.endDate(),
                request.weeklyHours(),
                !Boolean.FALSE.equals(request.isActive()),
                id);
    }

    public void deleteEmployeeContract(long id) {
        jdbc.update("delete from employee_contract where contract_id = ?", id);
    }

    private static final RowMapper<EmployeeJobRole> EMPLOYEE_JOB_ROLE = (row, index) -> new EmployeeJobRole(
            row.getLong("employee_job_role_id"),
            row.getObject("employee_id", Long.class),
            row.getObject("job_role_id", Long.class),
            row.getObject("assigned_date", LocalDate.class),
            row.getObject("expiry_date", LocalDate.class),
            row.getString("proficiency_level"));

    public List<EmployeeJobRole> listEmployeeJobRoles() {
        return jdbc.query("""
                select employee_job_role_id, employee_id, job_role_id, assigned_date, expiry_date, proficiency_level
                from employee_job_role order by employee_job_role_id
                """, EMPLOYEE_JOB_ROLE);
    }

    public EmployeeJobRole getEmployeeJobRole(long id) {
        return findEmployeeJobRole(id, false);
    }

    public EmployeeJobRole lockEmployeeJobRole(long id) {
        return findEmployeeJobRole(id, true);
    }

    private EmployeeJobRole findEmployeeJobRole(long id, boolean lock) {
        String sql = """
                select employee_job_role_id, employee_id, job_role_id, assigned_date, expiry_date, proficiency_level
                from employee_job_role where employee_job_role_id = ?
                """;
        return jdbc.query(sql + (lock ? " for update" : ""), EMPLOYEE_JOB_ROLE, id).stream().findFirst()
                .orElseThrow(() -> new ReferenceDataNotFoundException("Employee Job Role", id));
    }

    public EmployeeJobRole createEmployeeJobRole(EmployeeJobRole request) {
        return jdbc.queryForObject("""
                insert into employee_job_role (employee_id, job_role_id, assigned_date, expiry_date, proficiency_level)
                values (?, ?, ?, ?, ?)
                returning employee_job_role_id, employee_id, job_role_id, assigned_date, expiry_date, proficiency_level
                """, EMPLOYEE_JOB_ROLE,
                request.employeeId(),
                request.jobRoleId(),
                request.assignedDate(),
                request.expiryDate(),
                request.proficiencyLevel());
    }

    public EmployeeJobRole updateEmployeeJobRole(long id, EmployeeJobRole request) {
        return jdbc.queryForObject("""
                update employee_job_role
                set employee_id = ?,
                    job_role_id = ?,
                    assigned_date = ?,
                    expiry_date = ?,
                    proficiency_level = ?
                where employee_job_role_id = ?
                returning employee_job_role_id, employee_id, job_role_id, assigned_date, expiry_date, proficiency_level
                """, EMPLOYEE_JOB_ROLE,
                request.employeeId(),
                request.jobRoleId(),
                request.assignedDate(),
                request.expiryDate(),
                request.proficiencyLevel(),
                id);
    }

    public void deleteEmployeeJobRole(long id) {
        jdbc.update("delete from employee_job_role where employee_job_role_id = ?", id);
    }
}
