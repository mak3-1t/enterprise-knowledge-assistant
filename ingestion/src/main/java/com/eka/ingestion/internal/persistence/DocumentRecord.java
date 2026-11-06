package com.eka.ingestion.internal.persistence;

import java.time.Instant;
import java.util.UUID;

/** Ingestion-internal persistence record. Never serialize it directly as an HTTP response. */
public record DocumentRecord(
        UUID id,
        UUID tenantId,
        UUID uploadedBy,
        String filename,
        DocumentFormat format,
        String objectKey,
        StorageState storageState,
        IngestionStatus ingestionStatus,
        Long byteSize,
        String sha256,
        long version,
        Instant createdAt,
        Instant uploadedAt) {
    @Override
    public String toString() {
        return "DocumentRecord[REDACTED]";
    }
}
