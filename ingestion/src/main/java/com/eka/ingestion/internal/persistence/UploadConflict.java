package com.eka.ingestion.internal.persistence;

/** Stable business error without storage, filename or parser details. */
public class UploadConflict extends RuntimeException {
    public UploadConflict(String code) {
        super(code);
    }
}
