ALTER TABLE assessment_run ADD CONSTRAINT assessment_identity UNIQUE (id, case_id, dataset_id);
CREATE TABLE receipt_evidence (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES recall_case(id),
    base_assessment_id UUID NOT NULL,
    base_dataset_id UUID NOT NULL,
    receipt_id TEXT NOT NULL,
    document_text TEXT NOT NULL,
    document_sha256 CHAR(64) NOT NULL,
    proposal JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by TEXT,
    review_note TEXT,
    reviewed_at TIMESTAMPTZ,
    result_dataset_id UUID UNIQUE REFERENCES dataset(id),
    result_assessment_id UUID UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (base_assessment_id, case_id, base_dataset_id) REFERENCES assessment_run(id, case_id, dataset_id),
    FOREIGN KEY (base_dataset_id, receipt_id) REFERENCES receipt(dataset_id, id),
    FOREIGN KEY (result_assessment_id, case_id, result_dataset_id) REFERENCES assessment_run(id, case_id, dataset_id),
    CHECK (
      (status='PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_note IS NULL AND result_dataset_id IS NULL AND result_assessment_id IS NULL)
      OR (status='APPROVED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND review_note IS NOT NULL AND result_dataset_id IS NOT NULL AND result_assessment_id IS NOT NULL)
      OR (status='REJECTED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND review_note IS NOT NULL AND result_dataset_id IS NULL AND result_assessment_id IS NULL)
    )
);
CREATE INDEX receipt_evidence_case_idx ON receipt_evidence(case_id, created_at);
