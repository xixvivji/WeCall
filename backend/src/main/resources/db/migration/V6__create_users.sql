CREATE TABLE app_user (
    username VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('REVIEWER','OPERATOR')),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
