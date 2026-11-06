package com.eka.ingestion.internal.scan;

import java.util.UUID;

public record ScanJob(
        UUID id,
        UUID tenantId,
        UUID documentId,
        long generation,
        Long scanVersion,
        int attempts,
        UUID leaseToken) {}
