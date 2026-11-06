package com.eka.ingestion.internal.persistence;

import java.time.Instant;
import java.util.UUID;

/** Internal state; never serialize storage keys or request fingerprints as an API response. */
public record UploadRecord(
        UUID id,
        UUID tenantId,
        UUID uploadedBy,
        String filename,
        DocumentFormat format,
        String objectKey,
        long declaredSize,
        String requestedSha256,
        String fingerprint,
        UploadStatus status,
        long version,
        Instant expiresAt,
        Instant deletedAt,
        Instant purgedAt,
        Long byteSize,
        String sha256,
        String errorCode) {
    public String stagingKey() {
        return "staging/" + tenantId + "/" + id;
    }

    @Override
    public String toString() {
        return "UploadRecord[id=" + id + ", status=" + status + ", version=" + version + "]";
    }
}
