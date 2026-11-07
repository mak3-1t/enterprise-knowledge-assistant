package com.eka.ingestion.internal.embedding;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("eka.ingestion.embedding")
record EmbeddingSettings(
        @DefaultValue("http://localhost:11434") URI baseUrl,
        @DefaultValue("bge-m3:latest") String model,
        String modelVersion,
        @DefaultValue("8") int batchSize) {
    EmbeddingSettings {
        if (baseUrl == null
                || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null
                || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || model == null
                || !model.matches("[A-Za-z0-9_./:-]{1,128}")
                || modelVersion == null
                || !modelVersion.matches("[A-Za-z0-9_.:-]{1,128}")
                || batchSize < 1
                || batchSize > 16) {
            throw new IllegalArgumentException("Invalid document embedding settings");
        }
    }
}
