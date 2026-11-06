package com.eka.ingestion.internal.persistence;

/** Measured by the trusted storage adapter over immutable original bytes. */
public record VerifiedUpload(long bytes, String sha256) {}
