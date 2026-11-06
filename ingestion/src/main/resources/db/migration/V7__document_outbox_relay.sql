ALTER TABLE ingestion.documents ADD CONSTRAINT documents_tenant_identity UNIQUE (tenant_id, id);
ALTER TABLE ingestion.document_outbox
    ADD CONSTRAINT document_outbox_tenant_identity FOREIGN KEY (tenant_id, document_id)
        REFERENCES ingestion.documents(tenant_id, id),
    ADD CONSTRAINT document_outbox_version_nonnegative CHECK (document_version >= 0),
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    ADD COLUMN relay_lease_token UUID,
    ADD COLUMN relay_lease_until TIMESTAMPTZ,
    ADD COLUMN relay_attempts BIGINT NOT NULL DEFAULT 0 CHECK (relay_attempts >= 0),
    ADD COLUMN relay_available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN relay_error_code VARCHAR(40),
    ADD COLUMN broker_partition INTEGER CHECK (broker_partition >= 0),
    ADD COLUMN broker_offset BIGINT CHECK (broker_offset >= 0),
    ADD CONSTRAINT document_outbox_relay_lease CHECK
        ((relay_lease_token IS NULL) = (relay_lease_until IS NULL)),
    ADD CONSTRAINT document_outbox_broker_receipt CHECK
        ((published_at IS NULL AND broker_partition IS NULL AND broker_offset IS NULL)
         OR (published_at IS NOT NULL AND broker_partition IS NOT NULL AND broker_offset IS NOT NULL
             AND relay_lease_token IS NULL));

CREATE INDEX document_outbox_relay_ready_idx
    ON ingestion.document_outbox(relay_available_at, created_at, id) WHERE published_at IS NULL;
CREATE INDEX document_outbox_document_order_idx
    ON ingestion.document_outbox(document_id, document_version, id) WHERE published_at IS NULL;
