package com.eka.ingestion.internal.embedding;

import com.eka.ingestion.internal.chunking.SourceChunk;
import java.util.ArrayList;
import java.util.List;

final class EmbeddingBatch {
    static final int DIMENSIONS = 1024;
    private final EmbeddingProvider provider;
    private final EmbeddingSettings settings;

    EmbeddingBatch(EmbeddingProvider provider, EmbeddingSettings settings) {
        this.provider = provider;
        this.settings = settings;
    }

    List<EmbeddedChunk> embed(List<SourceChunk> chunks) {
        if (chunks.isEmpty() || chunks.size() > settings.batchSize()) {
            throw new EmbeddingFailure("EMBEDDING_BATCH_SIZE");
        }
        var first = chunks.getFirst();
        List<String> input = new ArrayList<>(chunks.size());
        for (var chunk : chunks) {
            if (!first.tenantId().equals(chunk.tenantId())
                    || !first.documentId().equals(chunk.documentId())
                    || first.sourceGeneration() != chunk.sourceGeneration()) {
                throw new EmbeddingFailure("EMBEDDING_BATCH_SCOPE");
            }
            String text = chunk.text();
            if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > 4096) {
                throw new EmbeddingFailure("EMBEDDING_INPUT_LIMIT");
            }
            input.add(text);
        }
        List<float[]> vectors = provider.embed(List.copyOf(input));
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new EmbeddingFailure("EMBEDDING_RESPONSE_COUNT");
        }
        List<EmbeddedChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var vector = vectors.get(i);
            validate(vector);
            result.add(
                    new EmbeddedChunk(
                            chunks.get(i), settings.model(), settings.modelVersion(), vector));
        }
        return List.copyOf(result);
    }

    private static void validate(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) {
            throw new EmbeddingFailure("EMBEDDING_DIMENSION");
        }
        boolean nonzero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new EmbeddingFailure("EMBEDDING_NONFINITE");
            }
            nonzero |= value != 0;
        }
        if (!nonzero) {
            throw new EmbeddingFailure("EMBEDDING_ZERO_VECTOR");
        }
    }

    record EmbeddedChunk(SourceChunk chunk, String model, String modelVersion, float[] vector) {
        EmbeddedChunk {
            vector = vector.clone();
        }

        @Override
        public float[] vector() {
            return vector.clone();
        }
    }
}
