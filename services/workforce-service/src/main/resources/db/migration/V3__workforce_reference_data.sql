CREATE TABLE department
(
    department_id BIGSERIAL PRIMARY KEY,
    department_name TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE job_role
(
    job_role_id BIGSERIAL PRIMARY KEY,
    role_name TEXT NOT NULL,
    job_role_description TEXT,
    is_certification_required BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE work_location
(
    work_location_id BIGSERIAL PRIMARY KEY,
    location_name TEXT NOT NULL,
    address_line_1 TEXT,
    address_line_2 TEXT,
    city TEXT NOT NULL,
    country TEXT NOT NULL,
    timezone TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);
