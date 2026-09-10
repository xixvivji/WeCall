CREATE TABLE recall_case (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    source_type TEXT NOT NULL CHECK (source_type IN ('SUPPLIER', 'OFFICIAL', 'INTERNAL')),
    source_text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE recall_condition (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES recall_case(id),
    dataset_id UUID NOT NULL REFERENCES dataset(id),
    version INTEGER NOT NULL CHECK (version > 0),
    definition JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED')),
    approved_by TEXT,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (case_id, version),
    UNIQUE (id, case_id, dataset_id),
    CHECK ((status='DRAFT' AND approved_by IS NULL AND approved_at IS NULL)
        OR (status='APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL))
);
CREATE TABLE assessment_run (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL,
    condition_id UUID NOT NULL,
    dataset_id UUID NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (condition_id, case_id, dataset_id) REFERENCES recall_condition(id, case_id, dataset_id)
);
CREATE INDEX assessment_case_idx ON assessment_run(case_id, created_at);
