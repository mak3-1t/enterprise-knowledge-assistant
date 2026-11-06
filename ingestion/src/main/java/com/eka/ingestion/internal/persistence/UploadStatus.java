package com.eka.ingestion.internal.persistence;

public enum UploadStatus {
    PENDING,
    UPLOADED,
    SCANNING,
    PROCESSING,
    READY,
    EXPIRED,
    REJECTED,
    FAILED,
    DELETED
}
