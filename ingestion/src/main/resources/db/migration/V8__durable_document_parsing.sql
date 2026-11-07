ALTER TABLE ingestion.documents ADD COLUMN source_generation BIGINT CHECK (source_generation >= 0);
ALTER TABLE ingestion.document_outbox DROP CONSTRAINT document_outbox_event_type_check;
ALTER TABLE ingestion.document_outbox ADD CONSTRAINT document_outbox_event_type_check CHECK
    (event_type IN ('UPLOAD_COMPLETED','DOCUMENT_DELETED','DOCUMENT_RESTORED','CONTENT_VERIFIED','SOURCE_PARSED'));

CREATE TABLE ingestion.document_parse_jobs (
    id UUID PRIMARY KEY REFERENCES ingestion.document_outbox(id),
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation BIGINT NOT NULL CHECK (generation >= 0),
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN
        ('PENDING','RUNNING','RETRY','SUCCEEDED','DEAD','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 4),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, document_id, generation),
    FOREIGN KEY (tenant_id, document_id) REFERENCES ingestion.documents(tenant_id, id),
    CHECK ((state = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX document_parse_claim_idx ON ingestion.document_parse_jobs(available_at, id)
    WHERE state IN ('PENDING','RETRY','RUNNING');

CREATE TABLE ingestion.document_source_sections (
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 1999),
    page INTEGER CHECK (page BETWEEN 1 AND 2000),
    source_text TEXT NOT NULL CHECK (length(source_text) BETWEEN 1 AND 2000000),
    parser_version VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id, document_id, generation, ordinal),
    FOREIGN KEY (tenant_id, document_id, generation)
        REFERENCES ingestion.document_parse_jobs(tenant_id, document_id, generation)
);

CREATE TABLE ingestion.document_intake_rejections (
    topic VARCHAR(249) NOT NULL,
    partition_id INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic, partition_id, record_offset)
);
