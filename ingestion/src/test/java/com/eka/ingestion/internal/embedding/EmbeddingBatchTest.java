package com.eka.ingestion.internal.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.internal.chunking.SourceChunk;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddingBatchTest {
    private final UUID tenant = UUID.randomUUID();
    private final UUID document = UUID.randomUUID();
    private final EmbeddingSettings settings =
            new EmbeddingSettings(
                    URI.create("http://localhost:11434"), "bge-m3:latest", "test-v1", 2);

    @Test
    void preservesSourceOrderAndProtectsValidatedVectors() {
        float[] first = vector(1);
        float[] second = vector(2);
        var batch =
                new EmbeddingBatch(
                        input -> {
                            assertThat(input).containsExactly("một", "hai");
                            return List.of(first, second);
                        },
                        settings);
        var chunks = batch.embed(List.of(chunk(tenant, 0, "một"), chunk(tenant, 1, "hai")));
        assertThat(chunks.get(1).chunk().ordinal()).isEqualTo(1);
        assertThat(chunks.get(1).modelVersion()).isEqualTo("test-v1");
        second[0] = 9;
        chunks.get(1).vector()[0] = 8;
        assertThat(chunks.get(1).vector()[0]).isEqualTo(2);
    }

    @Test
    void rejectsInvalidProviderResultsWithoutReturningPartialBatches() {
        var input = List.of(chunk(tenant, 0, "one"), chunk(tenant, 1, "two"));
        assertThatThrownBy(
                        () -> new EmbeddingBatch(text -> List.of(vector(1)), settings).embed(input))
                .hasMessage("EMBEDDING_RESPONSE_COUNT");
        assertThatThrownBy(
                        () ->
                                new EmbeddingBatch(
                                                text -> List.of(vector(1), new float[1]), settings)
                                        .embed(input))
                .hasMessage("EMBEDDING_DIMENSION");
        assertThatThrownBy(
                        () ->
                                new EmbeddingBatch(
                                                text -> List.of(vector(1), vector(Float.NaN)),
                                                settings)
                                        .embed(input))
                .hasMessage("EMBEDDING_NONFINITE");
        assertThatThrownBy(
                        () ->
                                new EmbeddingBatch(text -> List.of(vector(1), vector(0)), settings)
                                        .embed(input))
                .hasMessage("EMBEDDING_ZERO_VECTOR");
    }

    @Test
    void refusesCrossTenantAndOversizedInputBeforeProviderCall() {
        var batch =
                new EmbeddingBatch(
                        text -> {
                            throw new AssertionError("Provider must not be called");
                        },
                        settings);
        assertThatThrownBy(
                        () ->
                                batch.embed(
                                        List.of(
                                                chunk(tenant, 0, "one"),
                                                chunk(UUID.randomUUID(), 1, "two"))))
                .hasMessage("EMBEDDING_BATCH_SCOPE");
        assertThatThrownBy(() -> batch.embed(List.of(chunk(tenant, 0, "x".repeat(4097)))))
                .hasMessage("EMBEDDING_INPUT_LIMIT");
    }

    private SourceChunk chunk(UUID owner, int ordinal, String text) {
        return new SourceChunk(owner, document, 3, ordinal, 0, null, 0, text.length(), text);
    }

    private static float[] vector(float value) {
        float[] result = new float[EmbeddingBatch.DIMENSIONS];
        result[0] = value;
        return result;
    }
}
