package com.eka.ingestion.internal.scan;

public class ScanUnavailable extends RuntimeException {
    public ScanUnavailable(String code) {
        super(code);
    }
}
