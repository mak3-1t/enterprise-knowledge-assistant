\set ON_ERROR_STOP on

-- Use a database operator account and the exact FAILED document version from the incident.
WITH requeued AS (
    UPDATE ingestion.documents d SET upload_status = 'UPLOADED', ingestion_status = 'UPLOADED',
        source_generation = NULL, cleanup_after = NULL, error_code = NULL,
        version = d.version + 1, updated_at = CURRENT_TIMESTAMP
    WHERE d.tenant_id = :'tenant_id'::uuid AND d.id = :'document_id'::uuid
        AND d.version = :'failed_version'::bigint AND d.upload_status = 'FAILED'
        AND d.purged_at IS NULL AND d.cleanup_after > clock_timestamp()
        AND d.idempotency_key IS NOT NULL
        AND (
            (d.source_generation IS NULL AND EXISTS (
                SELECT 1 FROM ingestion.document_parse_jobs p
                WHERE p.tenant_id = d.tenant_id AND p.document_id = d.id
                    AND p.generation = d.version - 1 AND p.state = 'DEAD' AND p.error_code = d.error_code
            ))
            OR (d.source_generation IS NOT NULL AND EXISTS (
                SELECT 1 FROM ingestion.document_embedding_jobs e
                WHERE e.tenant_id = d.tenant_id AND e.document_id = d.id
                    AND e.generation = d.source_generation AND e.state = 'DEAD' AND e.error_code = d.error_code
            ))
        )
    RETURNING d.tenant_id, d.id, d.version
), cleared AS (
    DELETE FROM ingestion.document_source_sections s USING requeued d
    WHERE s.tenant_id = d.tenant_id AND s.document_id = d.id
)
INSERT INTO ingestion.document_outbox (id, tenant_id, document_id, document_version, event_type)
SELECT gen_random_uuid(), tenant_id, id, version, 'DOCUMENT_RESTORED' FROM requeued
RETURNING document_id, document_version;
