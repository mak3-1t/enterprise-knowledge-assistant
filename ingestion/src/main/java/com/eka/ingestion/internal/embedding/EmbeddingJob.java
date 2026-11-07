package com.eka.ingestion.internal.embedding;

import java.util.UUID;

record EmbeddingJob(
        UUID tenant, UUID document, long generation, long version, int attempts, UUID token) {}
