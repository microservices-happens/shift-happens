CREATE TABLE employee_contract
(
    contract_id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employee (employee_id),
    department_id BIGINT NOT NULL REFERENCES department (department_id),
    contract_type TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE CHECK (end_date >= start_date),
    weekly_hours INTEGER CHECK (weekly_hours >= 0),
    salary_amount NUMERIC(12, 2),
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_employee_contract_employee ON employee_contract (employee_id);

CREATE TABLE employee_job_role
(
    employee_job_role_id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employee (employee_id),
    job_role_id BIGINT NOT NULL REFERENCES job_role (job_role_id),
    assigned_date DATE NOT NULL,
    expiry_date DATE CHECK (expiry_date >= assigned_date),
    proficiency_level TEXT,
    UNIQUE (employee_id, job_role_id)
);
