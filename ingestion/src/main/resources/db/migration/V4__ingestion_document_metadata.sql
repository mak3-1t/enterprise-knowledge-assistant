CREATE SCHEMA ingestion;

CREATE TABLE ingestion.documents (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    uploaded_by UUID NOT NULL,
    filename VARCHAR(255) NOT NULL,
    format VARCHAR(4) NOT NULL,
    object_key VARCHAR(180) NOT NULL UNIQUE,
    storage_state VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
    ingestion_status VARCHAR(16),
    byte_size BIGINT,
    sha256 CHAR(64),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    uploaded_at TIMESTAMPTZ,
    CONSTRAINT documents_filename_valid CHECK (
        filename = btrim(filename) AND filename <> '' AND filename NOT IN ('.', '..')
        AND position('/' in filename) = 0 AND position(chr(92) in filename) = 0
        AND position(chr(10) in filename) = 0 AND position(chr(13) in filename) = 0),
    CONSTRAINT documents_format_valid CHECK (format IN ('PDF', 'DOCX', 'TXT', 'MD')),
    CONSTRAINT documents_key_owned CHECK (
        object_key = 'tenants/' || tenant_id::text || '/documents/' || id::text || '/original'),
    CONSTRAINT documents_state_valid CHECK (storage_state IN ('UPLOADING', 'STORED', 'FAILED')),
    CONSTRAINT documents_ingestion_valid CHECK (
        ingestion_status IS NULL OR ingestion_status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT documents_size_valid CHECK (byte_size IS NULL OR byte_size BETWEEN 1 AND 20971520),
    CONSTRAINT documents_hash_valid CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT documents_version_valid CHECK (version >= 0),
    CONSTRAINT documents_storage_consistent CHECK (
        (storage_state = 'STORED' AND byte_size IS NOT NULL AND sha256 IS NOT NULL
            AND uploaded_at IS NOT NULL AND ingestion_status IS NOT NULL)
        OR (storage_state IN ('UPLOADING', 'FAILED') AND byte_size IS NULL AND sha256 IS NULL
            AND uploaded_at IS NULL AND ingestion_status IS NULL))
);
CREATE INDEX documents_tenant_visible_idx ON ingestion.documents(tenant_id, id)
    WHERE storage_state = 'STORED';
