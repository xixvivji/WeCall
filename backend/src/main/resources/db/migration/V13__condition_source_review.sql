ALTER TABLE recall_condition ADD COLUMN source_version BIGINT CHECK(source_version>0);
ALTER TABLE assessment_run ADD COLUMN source_version BIGINT CHECK(source_version>0);
-- Old manual conditions/runs have no reliable source binding. Keep NULL rather than guessing.
ALTER TABLE recall_condition ADD COLUMN withdrawn_by TEXT;
ALTER TABLE recall_condition ADD COLUMN withdrawn_at TIMESTAMPTZ;
ALTER TABLE recall_condition ADD COLUMN withdrawal_note TEXT;
ALTER TABLE recall_condition DROP CONSTRAINT recall_condition_status_check;
ALTER TABLE recall_condition DROP CONSTRAINT recall_condition_check;
ALTER TABLE recall_condition ADD CHECK(status IN ('DRAFT','APPROVED','WITHDRAWN'));
ALTER TABLE recall_condition ADD CHECK(
    (status='DRAFT' AND approved_by IS NULL AND approved_at IS NULL AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND withdrawal_note IS NULL)
 OR (status='APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND withdrawn_at IS NULL AND withdrawn_by IS NULL AND withdrawal_note IS NULL)
 OR (status='WITHDRAWN' AND approved_by IS NULL AND approved_at IS NULL AND withdrawn_at IS NOT NULL AND withdrawn_by IS NOT NULL AND withdrawal_note IS NOT NULL));
ALTER TABLE recall_condition ADD UNIQUE(id,case_id);
CREATE TABLE condition_source_review (
    condition_id UUID NOT NULL,
    case_id UUID NOT NULL,
    source_version BIGINT NOT NULL,
    reviewer TEXT NOT NULL,
    note TEXT NOT NULL CHECK(length(trim(note))>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(condition_id,source_version),
    FOREIGN KEY(condition_id,case_id) REFERENCES recall_condition(id,case_id),
    FOREIGN KEY(case_id,source_version) REFERENCES source_revision(case_id,version)
);
CREATE VIEW condition_source_state AS
SELECT r.id,r.case_id,r.source_version,c.source_version AS current_source_version,
    (r.status<>'WITHDRAWN' AND r.source_version IS DISTINCT FROM c.source_version
        AND NOT EXISTS(SELECT 1 FROM condition_source_review v WHERE v.condition_id=r.id AND v.source_version=c.source_version)) AS review_required
FROM recall_condition r JOIN recall_case c ON c.id=r.case_id;
