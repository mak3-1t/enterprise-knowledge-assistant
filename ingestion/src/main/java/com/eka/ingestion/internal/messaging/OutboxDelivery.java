package com.eka.ingestion.internal.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable, replay-stable event identity. Contains no filename, storage key or document text. */
public record OutboxDelivery(
        UUID id,
        UUID tenantId,
        UUID documentId,
        long documentVersion,
        String eventType,
        Instant occurredAt,
        int schemaVersion,
        UUID leaseToken,
        long attempts) {
    public OutboxDelivery {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(documentId);
        Objects.requireNonNull(occurredAt);
        Objects.requireNonNull(leaseToken);
        if (documentVersion < 0
                || schemaVersion != 1
                || attempts < 1
                || !java.util.Set.of(
                                "UPLOAD_COMPLETED",
                                "DOCUMENT_RESTORED",
                                "DOCUMENT_DELETED",
                                "CONTENT_VERIFIED")
                        .contains(eventType)) {
            throw new IllegalArgumentException("Invalid document event");
        }
    }

    public String partitionKey() {
        return tenantId + "/" + documentId;
    }

    public record Receipt(int partition, long offset) {
        public Receipt {
            if (partition < 0 || offset < 0) {
                throw new IllegalArgumentException("Invalid broker receipt");
            }
        }
    }
}
