CREATE TABLE extraction_job (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES recall_case(id),
    requested_by TEXT NOT NULL,
    source_text TEXT NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    status TEXT NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    review_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING','ACCEPTED','DISMISSED')),
    response JSONB,
    raw_response TEXT,
    error_code TEXT,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    reviewed_by TEXT,
    review_note TEXT,
    reviewed_at TIMESTAMPTZ,
    condition_id UUID REFERENCES recall_condition(id),
    CHECK ((review_status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND condition_id IS NULL)
        OR (review_status='ACCEPTED' AND status='SUCCEEDED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND condition_id IS NOT NULL)
        OR (review_status='DISMISSED' AND status IN ('SUCCEEDED','FAILED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND condition_id IS NULL))
);
CREATE UNIQUE INDEX extraction_active_case_idx ON extraction_job(case_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX extraction_case_idx ON extraction_job(case_id,created_at);
