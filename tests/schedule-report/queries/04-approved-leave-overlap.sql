-- title: Approved leave still on the schedule
-- description: Approved leave that overlaps an active assignment. The leave-approval saga should have released these assignments, so every row is a saga or data problem.
SELECT lr.leave_request_id,
       CONCAT(e.first_name, ' ', e.last_name) AS employee,
       lt.leave_type_name,
       lr.start_date,
       lr.end_date,
       s.shift_id,
       s.shift_name,
       s.start_datetime,
       a.assignment_status
FROM leave_request lr
         JOIN employee e ON e.employee_id = lr.employee_id
         JOIN leave_type lt ON lt.leave_type_id = lr.leave_type_id
         JOIN shift_assignment a ON a.employee_id = lr.employee_id
         JOIN shift s ON s.shift_id = a.shift_id
WHERE lr.request_status = 'APPROVED'
  AND a.assignment_status NOT IN ('CANCELLED', 'NO_SHOW', 'RELEASED_FOR_LEAVE')
  AND s.shift_status <> 'CANCELLED'
  AND DATE(s.start_datetime) BETWEEN lr.start_date AND lr.end_date
ORDER BY lr.start_date, s.start_datetime;
