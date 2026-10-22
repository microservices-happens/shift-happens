-- Deliberate findings for the nightly shift-risk report.
--
-- The seed data is generated randomly, so a report could come out empty. These three rows guarantee
-- that every demo of the scheduled pipeline shows something, and each one maps to a real rule in
-- docs/architecture.md:
--   1. a shift inside the 24-hour locked window with no staff at all,
--   2. one employee assigned to two overlapping shifts (the Scheduling overlap rule),
--   3. approved leave that was never released from the schedule (the leave-approval saga).
--
-- They are inserted only by tests/schedule-report and never into a service database.
USE shift_happens;

SET @demo_employee := (SELECT MIN(employee_id) FROM employee);
SET @demo_role := (SELECT MIN(job_role_id) FROM job_role);
SET @demo_leave_type := (SELECT MIN(leave_type_id) FROM leave_type);

-- 1) Uncovered shift inside the locked window.
INSERT INTO shift (department_id, work_location_id, shift_name, start_datetime, end_datetime, shift_status)
VALUES (1, 1, 'DEMO: uncovered night shift', NOW() + INTERVAL 4 HOUR, NOW() + INTERVAL 12 HOUR, 'PLANNED');
SET @demo_shift := LAST_INSERT_ID();

INSERT INTO shift_required_job_role (shift_id, job_role_id, required_employee_count)
VALUES (@demo_shift, @demo_role, 2);

-- 2) The same employee in two overlapping shifts.
INSERT INTO shift (department_id, work_location_id, shift_name, start_datetime, end_datetime, shift_status)
VALUES (1, 1, 'DEMO: overlapping shift A',
        NOW() + INTERVAL 2 DAY + INTERVAL 8 HOUR, NOW() + INTERVAL 2 DAY + INTERVAL 16 HOUR, 'PLANNED'),
       (1, 1, 'DEMO: overlapping shift B',
        NOW() + INTERVAL 2 DAY + INTERVAL 12 HOUR, NOW() + INTERVAL 2 DAY + INTERVAL 20 HOUR, 'PLANNED');
SET @demo_shift_a := LAST_INSERT_ID();
SET @demo_shift_b := @demo_shift_a + 1;

INSERT INTO shift_assignment (shift_id, employee_id, assignment_status, assigned_datetime)
VALUES (@demo_shift_a, @demo_employee, 'ASSIGNED', NOW()),
       (@demo_shift_b, @demo_employee, 'ASSIGNED', NOW());

-- 3) Approved leave overlapping an assignment.
INSERT INTO leave_request (employee_id, leave_type_id, start_date, end_date, request_status, reason,
                           requested_datetime)
VALUES (@demo_employee, @demo_leave_type, CURDATE() + INTERVAL 2 DAY, CURDATE() + INTERVAL 4 DAY,
        'APPROVED', 'DEMO: approved leave overlapping an assignment', NOW());
