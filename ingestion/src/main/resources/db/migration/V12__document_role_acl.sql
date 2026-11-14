ALTER TABLE ingestion.documents
    ADD COLUMN reader_roles TEXT[] NOT NULL DEFAULT ARRAY['EDITOR', 'VIEWER']::TEXT[],
    ADD COLUMN acl_updated_at TIMESTAMPTZ;

ALTER TABLE ingestion.documents
    ADD CONSTRAINT documents_reader_roles_valid CHECK (
        reader_roles <@ ARRAY['EDITOR', 'VIEWER']::TEXT[]
        AND array_position(reader_roles, NULL) IS NULL
        AND cardinality(reader_roles) <= 2
    );

CREATE INDEX documents_reader_roles_gin_idx
    ON ingestion.documents USING GIN (reader_roles);
