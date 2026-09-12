ALTER TABLE app_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE account_security_event (
    id UUID PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    actor VARCHAR(64) NOT NULL,
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('PASSWORD_CHANGED','DISABLED','ENABLED')),
    note VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Keep audit identity even if an account is removed by a future retention process.
CREATE INDEX account_security_event_user_idx ON account_security_event(username,created_at DESC);
