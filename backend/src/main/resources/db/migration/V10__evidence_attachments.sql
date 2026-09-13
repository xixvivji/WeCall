ALTER TABLE receipt_evidence ADD CONSTRAINT evidence_case_identity UNIQUE(id,case_id);
ALTER TABLE response_task_proof ADD CONSTRAINT proof_task_identity UNIQUE(id,task_id);
CREATE TABLE evidence_attachment (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES recall_case(id),
    evidence_id UUID,
    task_id UUID,
    proof_id UUID,
    filename TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK(media_type IN ('application/pdf','image/png','image/jpeg')),
    byte_size BIGINT NOT NULL CHECK(byte_size > 0 AND byte_size <= 10485760),
    sha256 CHAR(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    uploaded_by TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(evidence_id,case_id) REFERENCES receipt_evidence(id,case_id),
    FOREIGN KEY(task_id,case_id) REFERENCES response_task(id,case_id),
    FOREIGN KEY(proof_id,task_id) REFERENCES response_task_proof(id,task_id),
    CHECK ((evidence_id IS NOT NULL AND task_id IS NULL AND proof_id IS NULL) OR (evidence_id IS NULL AND task_id IS NOT NULL AND proof_id IS NOT NULL))
);
CREATE INDEX attachment_evidence_idx ON evidence_attachment(case_id,evidence_id);
CREATE INDEX attachment_proof_idx ON evidence_attachment(case_id,proof_id);
CREATE TABLE attachment_event (
    id UUID PRIMARY KEY,
    attachment_id UUID NOT NULL REFERENCES evidence_attachment(id),
    event_type TEXT NOT NULL CHECK(event_type IN ('UPLOADED','DOWNLOAD_REQUESTED')),
    actor TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX attachment_event_file_idx ON attachment_event(attachment_id,created_at);
