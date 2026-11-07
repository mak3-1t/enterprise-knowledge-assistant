package com.eka.ingestion.internal.chunking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("eka.ingestion.chunking")
public record ChunkSettings(
        @DefaultValue("recursive") Strategy strategy,
        @DefaultValue("512") int size,
        @DefaultValue("64") int overlap) {
    public ChunkSettings {
        if (strategy == null || size < 64 || size > 4096 || overlap < 0 || overlap > size / 2) {
            throw new IllegalArgumentException("Invalid document chunk settings");
        }
    }

    public enum Strategy {
        FIXED,
        RECURSIVE
    }
}
