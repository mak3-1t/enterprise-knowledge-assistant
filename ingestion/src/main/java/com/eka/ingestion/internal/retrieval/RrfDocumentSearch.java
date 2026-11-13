package com.eka.ingestion.internal.retrieval;

import com.eka.ingestion.api.DocumentRetrieval.Match;
import com.eka.ingestion.api.HybridDocumentSearch;
import com.eka.ingestion.api.SemanticDocumentSearch;
import java.util.List;

/** Combines bounded vector and indexed lexical candidate lists for chat evidence. */
public final class RrfDocumentSearch implements HybridDocumentSearch {
    private static final int CANDIDATE_LIMIT = 10;
    private final SemanticDocumentSearch semantic;
    private final LexicalSectionSearch lexical;
    private final String model;
    private final String modelVersion;
    private final boolean enabled;

    public RrfDocumentSearch(
            SemanticDocumentSearch semantic,
            LexicalSectionSearch lexical,
            String model,
            String modelVersion,
            boolean enabled) {
        this.semantic = semantic;
        this.lexical = lexical;
        this.model = model;
        this.modelVersion = modelVersion;
        this.enabled = enabled;
    }

    @Override
    public List<Match> search(String question, int limit) {
        if (question == null
                || question.isBlank()
                || question.length() > 2000
                || limit < 1
                || limit > 10) {
            throw new IllegalArgumentException("Invalid hybrid search input");
        }
        List<Match> vector = semantic.search(question, CANDIDATE_LIMIT);
        if (!enabled) {
            return vector.stream().limit(limit).toList();
        }
        List<Match> terms = lexical.searchChunks(question, model, modelVersion, CANDIDATE_LIMIT);
        return ReciprocalRankFusion.fuse(vector, terms, limit);
    }
}
