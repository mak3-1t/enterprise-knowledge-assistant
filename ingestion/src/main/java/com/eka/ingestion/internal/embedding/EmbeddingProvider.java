package com.eka.ingestion.internal.embedding;

import java.util.List;

@FunctionalInterface
interface EmbeddingProvider {
    List<float[]> embed(List<String> input);
}
