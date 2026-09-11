ALTER TABLE assessment_run ADD CONSTRAINT assessment_case_identity UNIQUE (id, case_id);
CREATE TABLE response_task (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES recall_case(id),
    assessment_id UUID,
    task_type TEXT NOT NULL CHECK (task_type IN ('QUARANTINE','SHIPMENT_HOLD','SALES_HOLD','SUPPLIER_CHECK','RETURN_CONFIRMATION','NOTICE_PREPARATION')),
    target_type TEXT NOT NULL CHECK (target_type IN ('CASE','INVENTORY','SHIPMENT')),
    target_id TEXT,
    title TEXT NOT NULL,
    instructions TEXT NOT NULL,
    assignee TEXT,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','COMPLETED','CANCELLED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    review_round INTEGER NOT NULL DEFAULT 1 CHECK (review_round > 0),
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, case_id),
    FOREIGN KEY (assessment_id,case_id) REFERENCES assessment_run(id,case_id),
    CHECK ((target_type='CASE' AND target_id IS NULL) OR (target_type<>'CASE' AND target_id IS NOT NULL AND assessment_id IS NOT NULL)),
    CHECK ((status='COMPLETED' AND completed_at IS NOT NULL) OR (status<>'COMPLETED' AND completed_at IS NULL))
);
CREATE TABLE response_task_proof (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES response_task(id),
    review_round INTEGER NOT NULL CHECK (review_round > 0),
    evidence_text TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    submitted_by TEXT NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    reviewed_by TEXT,
    review_note TEXT,
    reviewed_at TIMESTAMPTZ,
    CHECK ((status='PENDING' AND reviewed_by IS NULL AND review_note IS NULL AND reviewed_at IS NULL)
      OR (status<>'PENDING' AND reviewed_by IS NOT NULL AND review_note IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE TABLE response_task_event (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES response_task(id),
    version BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    details JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(task_id,version)
);
CREATE INDEX response_task_case_idx ON response_task(case_id,created_at,id);
CREATE INDEX task_proof_round_idx ON response_task_proof(task_id,review_round);
