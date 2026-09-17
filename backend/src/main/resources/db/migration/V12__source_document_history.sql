ALTER TABLE recall_case ADD COLUMN source_version BIGINT NOT NULL DEFAULT 1 CHECK(source_version>0);
CREATE TABLE source_revision (
    case_id UUID NOT NULL REFERENCES recall_case(id),
    version BIGINT NOT NULL CHECK(version>0),
    source_text TEXT NOT NULL CHECK(length(source_text) BETWEEN 1 AND 100000),
    actor TEXT,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(case_id,version)
);
-- Historical authors were not recorded; do not invent them.
INSERT INTO source_revision(case_id,version,source_text,actor,note,created_at)
SELECT id,1,source_text,NULL,'기존 사건 원문 이전: 등록자 기록 없음',created_at FROM recall_case;
CREATE TABLE source_document (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL UNIQUE REFERENCES recall_case(id),
    filename TEXT NOT NULL,
    byte_size BIGINT NOT NULL CHECK(byte_size BETWEEN 1 AND 10485760),
    sha256 CHAR(64) NOT NULL,
    extracted_text TEXT NOT NULL CHECK(length(extracted_text) BETWEEN 1 AND 100000),
    pages INTEGER NOT NULL CHECK(pages BETWEEN 1 AND 50),
    uploaded_by TEXT NOT NULL,
    scan_status TEXT NOT NULL CHECK(scan_status IN ('CLEAN','NOT_SCANNED')),
    scan_engine TEXT,
    scanned_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE source_document_event (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES source_document(id),
    event_type TEXT NOT NULL CHECK(event_type IN ('UPLOADED','DOWNLOAD_REQUESTED')),
    actor TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE extraction_job ADD COLUMN source_version BIGINT NOT NULL DEFAULT 1 CHECK(source_version>0);
