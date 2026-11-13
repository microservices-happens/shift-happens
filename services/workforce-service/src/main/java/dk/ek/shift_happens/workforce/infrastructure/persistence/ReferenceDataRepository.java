package dk.ek.shift_happens.workforce.infrastructure.persistence;

import dk.ek.shift_happens.workforce.api.dto.Department;
import dk.ek.shift_happens.workforce.api.dto.JobRole;
import dk.ek.shift_happens.workforce.api.dto.WorkLocation;
import dk.ek.shift_happens.workforce.application.ReferenceDataNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ReferenceDataRepository {

    private final JdbcTemplate jdbc;

    ReferenceDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Department> DEPARTMENT = (row, index) -> new Department(
            row.getLong("department_id"),
            row.getString("department_name"),
            row.getBoolean("is_active"));

    public List<Department> listDepartments() {
        return jdbc.query("select * from department order by department_id", DEPARTMENT);
    }

    public Department createDepartment(Department request) {
        return jdbc.queryForObject("""
                insert into department (department_name, is_active)
                values (?, ?)
                returning *
                """, DEPARTMENT,
                request.departmentName(),
                request.isActive() == null ? true : request.isActive());
    }

    public Department getDepartment(long id) {
        return requireRow(jdbc.query("select * from department where department_id = ?", DEPARTMENT, id),
                "Department", id);
    }

    public Department updateDepartment(long id, Department request) {
        return requireRow(jdbc.query("""
                update department
                set department_name = ?,
                    is_active = coalesce(?, is_active)
                where department_id = ?
                returning *
                """, DEPARTMENT,
                request.departmentName(),
                request.isActive(),
                id),
                "Department", id);
    }

    public void deleteDepartment(long id) {
        requireRow(jdbc.update("delete from department where department_id = ?", id),
                "Department", id);
    }

    private static final RowMapper<JobRole> JOB_ROLE = (row, index) -> new JobRole(
            row.getLong("job_role_id"),
            row.getString("role_name"),
            row.getString("job_role_description"),
            row.getBoolean("is_certification_required"));

    public List<JobRole> listJobRoles() {
        return jdbc.query("select * from job_role order by job_role_id", JOB_ROLE);
    }

    public JobRole createJobRole(JobRole request) {
        return jdbc.queryForObject("""
                insert into job_role (role_name, job_role_description, is_certification_required)
                values (?, ?, ?)
                returning *
                """, JOB_ROLE,
                request.roleName(),
                request.jobRoleDescription(),
                request.isCertificationRequired() == null ? false : request.isCertificationRequired());
    }

    public JobRole getJobRole(long id) {
        return requireRow(jdbc.query("select * from job_role where job_role_id = ?", JOB_ROLE, id),
                "Job Role", id);
    }

    public JobRole updateJobRole(long id, JobRole request) {
        return requireRow(jdbc.query("""
                update job_role
                set role_name = ?,
                    job_role_description = ?,
                    is_certification_required = coalesce(?, is_certification_required)
                where job_role_id = ?
                returning *
                """, JOB_ROLE,
                request.roleName(),
                request.jobRoleDescription(),
                request.isCertificationRequired(),
                id),
                "Job Role", id);
    }

    public void deleteJobRole(long id) {
        requireRow(jdbc.update("delete from job_role where job_role_id = ?", id),
                "Job Role", id);
    }

    private static final RowMapper<WorkLocation> WORK_LOCATION = (row, index) -> new WorkLocation(
            row.getLong("work_location_id"),
            row.getString("location_name"),
            row.getString("address_line_1"),
            row.getString("address_line_2"),
            row.getString("city"),
            row.getString("country"),
            row.getString("timezone"),
            row.getBoolean("is_active"));

    public List<WorkLocation> listWorkLocations() {
        return jdbc.query("select * from work_location order by work_location_id", WORK_LOCATION);
    }

    public WorkLocation createWorkLocation(WorkLocation request) {
        return jdbc.queryForObject("""
                insert into work_location (location_name, address_line_1, address_line_2, city, country, timezone, is_active)
                values (?, ?, ?, ?, ?, ?, ?)
                returning *
                """, WORK_LOCATION,
                request.locationName(),
                request.addressLine1(),
                request.addressLine2(),
                request.city(),
                request.country(),
                request.timezone(),
                request.isActive() == null ? true : request.isActive());
    }

    public WorkLocation getWorkLocation(long id) {
        return requireRow(jdbc.query("select * from work_location where work_location_id = ?", WORK_LOCATION, id),
                "Work Location", id);
    }

    public WorkLocation updateWorkLocation(long id, WorkLocation request) {
        return requireRow(jdbc.query("""
                update work_location
                set location_name = ?,
                    address_line_1 = ?,
                    address_line_2 = ?,
                    city = ?,
                    country = ?,
                    timezone = ?,
                    is_active = coalesce(?, is_active)
                where work_location_id = ?
                returning *
                """, WORK_LOCATION,
                request.locationName(),
                request.addressLine1(),
                request.addressLine2(),
                request.city(),
                request.country(),
                request.timezone(),
                request.isActive(),
                id),
                "Work Location", id);
    }

    @Transactional
    public void deleteWorkLocation(long id) {
        // Keep employee writes from changing the result between the check and the delete.
        jdbc.execute("lock table employee in share mode");
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from employee where primary_work_location_id = ?)
                """, Boolean.class, id))) {
            // Preserve 404 when a legacy employee record points to a nonexistent location.
            getWorkLocation(id);
            throw new DataIntegrityViolationException("Work location is an employee's primary location");
        }
        requireRow(jdbc.update("delete from work_location where work_location_id = ?", id),
                "Work Location", id);
    }

    private static <T> T requireRow(List<T> rows, String resource, long id) {
        requireRow(rows.size(), resource, id);
        return rows.getFirst();
    }

    private static void requireRow(int affected, String resource, long id) {
        if (affected == 0) {
            throw new ReferenceDataNotFoundException(resource, id);
        }
    }
}
