-- title: Understaffed upcoming shifts
-- description: PLANNED shifts inside the horizon whose active assignments are fewer than the required staff.
SELECT s.shift_id,
       s.shift_name,
       s.start_datetime,
       d.department_name,
       GROUP_CONCAT(DISTINCT jr.role_name ORDER BY jr.role_name SEPARATOR ', ') AS required_roles,
       SUM(srjr.required_employee_count)                                     AS required_staff,
       COUNT(DISTINCT a.shift_assignment_id)                                 AS assigned_staff,
       SUM(srjr.required_employee_count) - COUNT(DISTINCT a.shift_assignment_id) AS missing_staff
FROM shift s
         JOIN department d ON d.department_id = s.department_id
         JOIN shift_required_job_role srjr ON srjr.shift_id = s.shift_id
         JOIN job_role jr ON jr.job_role_id = srjr.job_role_id
         LEFT JOIN shift_assignment a
                   ON a.shift_id = s.shift_id
                       AND a.assignment_status NOT IN ('CANCELLED', 'NO_SHOW')
WHERE s.shift_status = 'PLANNED'
  AND s.start_datetime >= NOW()
  AND s.start_datetime < NOW() + INTERVAL __HORIZON_DAYS__ DAY
GROUP BY s.shift_id, s.shift_name, s.start_datetime, d.department_name
HAVING assigned_staff < required_staff
ORDER BY s.start_datetime, s.shift_id;
