-- title: Double-booked employees
-- description: The same employee holds two active assignments in overlapping shifts, which the Scheduling rules forbid.
SELECT e.employee_id,
       CONCAT(e.first_name, ' ', e.last_name) AS employee,
       s1.shift_id                            AS shift_a,
       s1.start_datetime                      AS a_start,
       s1.end_datetime                        AS a_end,
       s2.shift_id                            AS shift_b,
       s2.start_datetime                      AS b_start,
       s2.end_datetime                        AS b_end
FROM shift_assignment a1
         JOIN shift_assignment a2
              ON a2.employee_id = a1.employee_id
                  AND a1.shift_assignment_id < a2.shift_assignment_id
         JOIN shift s1 ON s1.shift_id = a1.shift_id
         JOIN shift s2 ON s2.shift_id = a2.shift_id
         JOIN employee e ON e.employee_id = a1.employee_id
WHERE a1.assignment_status NOT IN ('CANCELLED', 'NO_SHOW')
  AND a2.assignment_status NOT IN ('CANCELLED', 'NO_SHOW')
  AND s1.shift_status <> 'CANCELLED'
  AND s2.shift_status <> 'CANCELLED'
  AND s1.start_datetime < s2.end_datetime
  AND s2.start_datetime < s1.end_datetime
ORDER BY e.last_name, s1.start_datetime;
