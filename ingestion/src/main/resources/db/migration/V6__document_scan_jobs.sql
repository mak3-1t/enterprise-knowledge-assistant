CREATE TABLE ingestion.document_scan_jobs (
    id UUID PRIMARY KEY REFERENCES ingestion.document_outbox(id),
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL REFERENCES ingestion.documents(id),
    generation BIGINT NOT NULL CHECK (generation >= 0),
    scan_version BIGINT,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN
        ('PENDING','RUNNING','RETRY','SUCCEEDED','REJECTED','DEAD','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 4),
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, generation),
    CONSTRAINT scan_lease_consistent CHECK
        ((state = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
         OR (state <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
    CONSTRAINT scan_version_valid CHECK (scan_version IS NULL OR scan_version = generation + 1)
);
CREATE INDEX document_scan_claim_idx ON ingestion.document_scan_jobs(available_at, id)
    WHERE state IN ('PENDING','RETRY','RUNNING');

ALTER TABLE ingestion.document_outbox ADD COLUMN scan_dispatched_at TIMESTAMPTZ;
ALTER TABLE ingestion.document_outbox DROP CONSTRAINT document_outbox_event_type_check;
ALTER TABLE ingestion.document_outbox ADD CONSTRAINT document_outbox_event_type_check CHECK
    (event_type IN ('UPLOAD_COMPLETED','DOCUMENT_DELETED','DOCUMENT_RESTORED','CONTENT_VERIFIED'));
CREATE INDEX document_outbox_scan_idx ON ingestion.document_outbox(created_at, id)
    WHERE scan_dispatched_at IS NULL AND event_type IN
        ('UPLOAD_COMPLETED','DOCUMENT_DELETED','DOCUMENT_RESTORED');
