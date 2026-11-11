-- Local login (P2.1). The hash is bcrypt(pepper + password); the pepper is never stored here.
-- NULL means no password has been set yet, so the account cannot log in with a password.
ALTER TABLE account ADD COLUMN password_hash VARCHAR(255);

-- Names from the employee snapshot, returned by POST /v1/auth/login like the monolith did.
ALTER TABLE account ADD COLUMN first_name VARCHAR(100);
ALTER TABLE account ADD COLUMN last_name VARCHAR(100);

-- Login looks accounts up by email regardless of case.
CREATE UNIQUE INDEX uq_account_email_lower ON account (lower(email));
