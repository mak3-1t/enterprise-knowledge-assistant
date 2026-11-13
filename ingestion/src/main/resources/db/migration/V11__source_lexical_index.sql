ALTER TABLE ingestion.document_source_sections
    ADD COLUMN lexical_terms tsvector
    GENERATED ALWAYS AS (to_tsvector('pg_catalog.simple', left(source_text, 64000))) STORED;

CREATE INDEX document_source_lexical_terms_idx
    ON ingestion.document_source_sections USING GIN (lexical_terms);
