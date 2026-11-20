CREATE TABLE employee_terms_refresh
(
    employee_id BIGINT PRIMARY KEY REFERENCES employee (employee_id),
    due_on DATE NOT NULL,
    evaluated_on DATE
);

CREATE INDEX idx_employee_terms_refresh_due ON employee_terms_refresh (due_on, employee_id);

-- Refresh existing projections once because their last evaluated date is unknown.
INSERT INTO employee_terms_refresh (employee_id, due_on)
SELECT e.employee_id, (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date
FROM employee e
WHERE NOT e.deleted
  AND (EXISTS (SELECT 1 FROM employee_contract c WHERE c.employee_id = e.employee_id)
       OR EXISTS (SELECT 1 FROM employee_job_role r WHERE r.employee_id = e.employee_id));
