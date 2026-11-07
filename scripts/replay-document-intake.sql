\set ON_ERROR_STOP on

-- Run as an authorized ingestion operator with psql -v event_id=<uuid> -f this-file.
-- Use only the canonical outbox event and lock the document before checking its version.
WITH eligible AS (
    SELECT e.id, e.tenant_id, e.document_id, e.document_version
    FROM ingestion.document_outbox e
    JOIN ingestion.documents d ON d.tenant_id = e.tenant_id AND d.id = e.document_id
    WHERE e.id = :'event_id'::uuid
        AND e.event_type = 'CONTENT_VERIFIED' AND e.schema_version = 1
        AND d.upload_status = 'PROCESSING' AND d.version = e.document_version
        AND d.source_generation IS NULL
    FOR UPDATE OF d
)
INSERT INTO ingestion.document_parse_jobs (id, tenant_id, document_id, generation)
SELECT id, tenant_id, document_id, document_version FROM eligible
ON CONFLICT DO NOTHING
RETURNING id AS replayed_event_id;
