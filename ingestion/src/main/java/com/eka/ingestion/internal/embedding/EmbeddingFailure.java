package com.eka.ingestion.internal.embedding;

final class EmbeddingFailure extends RuntimeException {
    EmbeddingFailure(String code) {
        super(code);
    }
}
