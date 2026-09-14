-- Historical attachments must never be relabeled as scanned.
ALTER TABLE evidence_attachment
    ADD COLUMN scan_status TEXT NOT NULL DEFAULT 'NOT_SCANNED' CHECK(scan_status IN ('NOT_SCANNED','CLEAN')),
    ADD COLUMN scan_engine TEXT,
    ADD COLUMN scanned_at TIMESTAMPTZ,
    ADD CONSTRAINT attachment_scan_receipt CHECK(
        (scan_status='NOT_SCANNED' AND scan_engine IS NULL AND scanned_at IS NULL)
        OR (scan_status='CLEAN' AND scan_engine IS NOT NULL AND scanned_at IS NOT NULL));
