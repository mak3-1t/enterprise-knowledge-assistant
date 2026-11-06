package com.eka.ingestion.internal.persistence;

/** Only UPLOADED is produced here. Pipeline transitions are implemented in Sprint 2. */
public enum IngestionStatus {
    UPLOADED,
    PROCESSING,
    READY,
    FAILED
}
