-- Google/GitHub login (P2.3).

-- A started login: the state sent to the provider and the PKCE verifier that never leaves this
-- service. Kept here, not in an HTTP session, so any replica can handle the callback. A row is
-- deleted when its callback arrives (single use) or when it has expired.
CREATE TABLE oidc_login_attempt
(
    state         VARCHAR(64)  PRIMARY KEY,
    provider      VARCHAR(20)  NOT NULL,
    code_verifier VARCHAR(128) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_oidc_login_attempt_expires_at ON oidc_login_attempt (expires_at);

-- Links a provider account (provider + its stable subject id) to an employee after the first login
-- by verified email. Later logins match on the link, so an email change does not lock anyone out.
CREATE TABLE external_identity
(
    provider    VARCHAR(20)  NOT NULL,
    subject     VARCHAR(255) NOT NULL,
    employee_id BIGINT       NOT NULL REFERENCES account (employee_id),
    linked_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (provider, subject)
);
