package com.eka.ingestion.internal.scan;

/** Stable safe code only; never include scanner signatures, paths or parser diagnostics. */
public class InspectionRejected extends RuntimeException {
    public InspectionRejected(String code) {
        super(code);
    }
}
