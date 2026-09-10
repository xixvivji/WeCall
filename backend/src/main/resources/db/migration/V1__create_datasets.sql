CREATE TABLE dataset (
    id UUID PRIMARY KEY,
    as_of TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE product (
    dataset_id UUID NOT NULL REFERENCES dataset(id),
    id TEXT NOT NULL,
    name TEXT NOT NULL,
    manufacturer TEXT NOT NULL,
    pack_size TEXT NOT NULL,
    unit TEXT NOT NULL CHECK (unit = 'EA'),
    PRIMARY KEY (dataset_id, id)
);
CREATE TABLE receipt (
    dataset_id UUID NOT NULL,
    id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    lot_number TEXT,
    expiry_date DATE,
    received_quantity BIGINT NOT NULL CHECK (received_quantity >= 0),
    received_at DATE NOT NULL,
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, id, product_id),
    FOREIGN KEY (dataset_id, product_id) REFERENCES product(dataset_id, id)
);
CREATE TABLE inventory (
    dataset_id UUID NOT NULL,
    id TEXT NOT NULL,
    receipt_id TEXT NOT NULL,
    warehouse TEXT NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    hold_status TEXT NOT NULL CHECK (hold_status IN ('NONE', 'HELD')),
    PRIMARY KEY (dataset_id, id),
    FOREIGN KEY (dataset_id, receipt_id) REFERENCES receipt(dataset_id, id)
);
CREATE TABLE shipment (
    dataset_id UUID NOT NULL,
    id TEXT NOT NULL,
    order_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity >= 0),
    shipped_at DATE NOT NULL,
    PRIMARY KEY (dataset_id, id),
    UNIQUE (dataset_id, id, product_id),
    FOREIGN KEY (dataset_id, product_id) REFERENCES product(dataset_id, id)
);
CREATE TABLE shipment_allocation (
    dataset_id UUID NOT NULL,
    id TEXT NOT NULL,
    shipment_id TEXT NOT NULL,
    receipt_id TEXT NOT NULL,
    product_id TEXT NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (dataset_id, id),
    FOREIGN KEY (dataset_id, shipment_id, product_id) REFERENCES shipment(dataset_id, id, product_id),
    FOREIGN KEY (dataset_id, receipt_id, product_id) REFERENCES receipt(dataset_id, id, product_id)
);
CREATE INDEX inventory_receipt_idx ON inventory(dataset_id, receipt_id);
CREATE INDEX allocation_shipment_idx ON shipment_allocation(dataset_id, shipment_id);
CREATE INDEX allocation_receipt_idx ON shipment_allocation(dataset_id, receipt_id);
