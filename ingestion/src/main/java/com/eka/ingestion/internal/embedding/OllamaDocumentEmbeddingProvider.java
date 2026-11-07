package com.eka.ingestion.internal.embedding;

import java.util.List;
import java.util.Map;
import org.springframework.ai.ollama.api.OllamaApi;

final class OllamaDocumentEmbeddingProvider implements EmbeddingProvider {
    private final OllamaApi api;
    private final String model;

    OllamaDocumentEmbeddingProvider(OllamaApi api, String model) {
        this.api = api;
        this.model = model;
    }

    @Override
    public List<float[]> embed(List<String> input) {
        OllamaApi.EmbeddingsResponse response;
        try {
            response =
                    api.embed(
                            new OllamaApi.EmbeddingsRequest(
                                    model, input, null, Map.of(), false, null));
        } catch (RuntimeException failure) {
            throw new EmbeddingFailure("EMBEDDING_PROVIDER_UNAVAILABLE");
        }
        if (response == null || !model.equals(response.model())) {
            throw new EmbeddingFailure("EMBEDDING_MODEL_MISMATCH");
        }
        return response.embeddings();
    }
}
