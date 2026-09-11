ALTER TABLE recall_case ADD COLUMN status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED'));
ALTER TABLE recall_case ADD COLUMN lifecycle_version BIGINT NOT NULL DEFAULT 0 CHECK (lifecycle_version >= 0);
ALTER TABLE recall_case ADD COLUMN closed_at TIMESTAMPTZ;
ALTER TABLE recall_case ADD CONSTRAINT closure_state CHECK ((status='OPEN' AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL));
CREATE TABLE case_lifecycle_event (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES recall_case(id),
    version BIGINT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('CLOSED','REOPENED')),
    assessment_id UUID,
    reviewer TEXT NOT NULL,
    note TEXT NOT NULL,
    snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(case_id,version),
    FOREIGN KEY (assessment_id,case_id) REFERENCES assessment_run(id,case_id),
    CHECK (event_type <> 'CLOSED' OR assessment_id IS NOT NULL)
);
