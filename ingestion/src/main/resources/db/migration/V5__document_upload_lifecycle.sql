ALTER TABLE ingestion.documents
    ADD COLUMN upload_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN declared_size BIGINT,
    ADD COLUMN requested_sha256 CHAR(64),
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN request_fingerprint CHAR(64),
    ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '1 hour'),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN restore_status VARCHAR(16),
    ADD COLUMN cleanup_after TIMESTAMPTZ,
    ADD COLUMN purged_at TIMESTAMPTZ,
    ADD COLUMN error_code VARCHAR(40);

UPDATE ingestion.documents SET upload_status = CASE
    WHEN storage_state = 'STORED' THEN 'UPLOADED'
    WHEN storage_state = 'FAILED' THEN 'FAILED' ELSE 'PENDING' END;

ALTER TABLE ingestion.documents
    ADD CONSTRAINT documents_upload_status CHECK (upload_status IN
        ('PENDING','UPLOADED','SCANNING','PROCESSING','READY','EXPIRED','REJECTED','FAILED','DELETED')),
    ADD CONSTRAINT documents_declared_size CHECK (declared_size BETWEEN 1 AND 20971520),
    ADD CONSTRAINT documents_requested_sha CHECK (requested_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT documents_managed_intent CHECK (idempotency_key IS NULL OR
        (declared_size IS NOT NULL AND request_fingerprint IS NOT NULL
            AND request_fingerprint ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT documents_deleted_consistent CHECK
        ((upload_status = 'DELETED') = (deleted_at IS NOT NULL)),
    ADD CONSTRAINT documents_purged_terminal CHECK (purged_at IS NULL OR upload_status IN
        ('DELETED','EXPIRED','REJECTED','FAILED'));

CREATE UNIQUE INDEX documents_upload_idempotency_idx
    ON ingestion.documents(tenant_id, uploaded_by, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX documents_cleanup_idx ON ingestion.documents(cleanup_after, id)
    WHERE cleanup_after IS NOT NULL AND purged_at IS NULL;
CREATE INDEX documents_expiry_idx ON ingestion.documents(expires_at, id)
    WHERE upload_status = 'PENDING';

CREATE TABLE ingestion.upload_quotas (
    tenant_id UUID NOT NULL,
    uploaded_by UUID NOT NULL,
    reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK (reserved_bytes BETWEEN 0 AND 524288000),
    reserved_files INTEGER NOT NULL DEFAULT 0 CHECK (reserved_files BETWEEN 0 AND 100),
    PRIMARY KEY (tenant_id, uploaded_by)
);

CREATE TABLE ingestion.document_outbox (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL REFERENCES ingestion.documents(id),
    document_version BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL CHECK (event_type IN
        ('UPLOAD_COMPLETED','DOCUMENT_DELETED','DOCUMENT_RESTORED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    UNIQUE (document_id, document_version, event_type)
);
CREATE INDEX document_outbox_pending_idx ON ingestion.document_outbox(created_at, id)
    WHERE published_at IS NULL;
