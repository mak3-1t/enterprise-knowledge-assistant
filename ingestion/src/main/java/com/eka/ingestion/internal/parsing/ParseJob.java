package com.eka.ingestion.internal.parsing;

import java.util.UUID;

public record ParseJob(
        UUID id, UUID tenantId, UUID documentId, long generation, int attempts, UUID leaseToken) {}
