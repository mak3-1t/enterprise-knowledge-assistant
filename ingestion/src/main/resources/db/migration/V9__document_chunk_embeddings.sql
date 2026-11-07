CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE ingestion.document_embedding_jobs (
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    state VARCHAR(12) NOT NULL CHECK (state IN ('RUNNING','RETRY','SUCCEEDED','DEAD','CANCELLED')),
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 4),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error_code VARCHAR(40),
    PRIMARY KEY (tenant_id, document_id, generation),
    FOREIGN KEY (tenant_id, document_id, generation)
        REFERENCES ingestion.document_parse_jobs(tenant_id, document_id, generation) ON DELETE CASCADE,
    CHECK ((state = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL))
);

CREATE TABLE ingestion.document_chunk_embeddings (
    tenant_id UUID NOT NULL,
    document_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 4095),
    section_ordinal INTEGER NOT NULL,
    start_offset INTEGER NOT NULL CHECK (start_offset >= 0),
    end_offset INTEGER NOT NULL CHECK (end_offset > start_offset),
    model VARCHAR(128) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    embedding vector(1024) NOT NULL,
    PRIMARY KEY (tenant_id, document_id, generation, ordinal),
    FOREIGN KEY (tenant_id, document_id, generation, section_ordinal)
        REFERENCES ingestion.document_source_sections(tenant_id, document_id, generation, ordinal)
        ON DELETE CASCADE
);

CREATE INDEX document_embedding_candidates_idx ON ingestion.documents(updated_at, id)
    WHERE upload_status = 'PROCESSING' AND source_generation IS NOT NULL;
