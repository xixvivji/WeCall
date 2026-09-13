ALTER TABLE dataset ADD COLUMN uploaded_by TEXT;
CREATE TABLE dataset_source_file (
    dataset_id UUID NOT NULL REFERENCES dataset(id),
    file_type TEXT NOT NULL CHECK (file_type IN ('products','receipts','inventory','shipments','shipment_allocations')),
    filename TEXT,
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    byte_size BIGINT NOT NULL CHECK (byte_size >= 0),
    row_count INTEGER NOT NULL CHECK (row_count >= 0),
    PRIMARY KEY(dataset_id,file_type)
);
