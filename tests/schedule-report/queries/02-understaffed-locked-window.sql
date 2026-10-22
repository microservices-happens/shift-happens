-- title: Understaffed within 24 hours (locked-window risk)
-- description: Shifts starting within 24 hours that are short of staff. A shift inside this window is locked, so the leave saga cannot release it; it needs a replacement or a manager decision today.
SELECT s.shift_id,
       s.shift_name,
       s.start_datetime,
       d.department_name,
       SUM(srjr.required_employee_count)                                         AS required_staff,
       COUNT(DISTINCT a.shift_assignment_id)                                     AS assigned_staff,
       SUM(srjr.required_employee_count) - COUNT(DISTINCT a.shift_assignment_id) AS missing_staff
FROM shift s
         JOIN department d ON d.department_id = s.department_id
         JOIN shift_required_job_role srjr ON srjr.shift_id = s.shift_id
         LEFT JOIN shift_assignment a
                   ON a.shift_id = s.shift_id
                       AND a.assignment_status NOT IN ('CANCELLED', 'NO_SHOW')
WHERE s.shift_status = 'PLANNED'
  AND s.start_datetime >= NOW()
  AND s.start_datetime < NOW() + INTERVAL 24 HOUR
GROUP BY s.shift_id, s.shift_name, s.start_datetime, d.department_name
HAVING assigned_staff < required_staff
ORDER BY s.start_datetime, s.shift_id;
