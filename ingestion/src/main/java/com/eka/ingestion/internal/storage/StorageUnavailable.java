package com.eka.ingestion.internal.storage;

public class StorageUnavailable extends RuntimeException {
    public StorageUnavailable() {
        super("STORAGE_UNAVAILABLE");
    }
}
