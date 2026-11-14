package com.eka.ingestion.internal.retrieval;

import com.eka.ingestion.api.DocumentReranker;
import com.eka.ingestion.api.DocumentRetrieval.Match;
import com.eka.ingestion.api.HybridDocumentSearch;
import com.eka.ingestion.api.SemanticDocumentSearch;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Combines bounded vector and indexed lexical candidate lists for chat evidence. */
public final class RrfDocumentSearch implements HybridDocumentSearch {
    private static final int CANDIDATE_LIMIT = 10;
    private final SemanticDocumentSearch semantic;
    private final LexicalSectionSearch lexical;
    private final String model;
    private final String modelVersion;
    private final boolean enabled;
    private final DocumentReranker reranker;
    private final boolean rerankingEnabled;
    private final RerankingEvents events;

    public RrfDocumentSearch(
            SemanticDocumentSearch semantic,
            LexicalSectionSearch lexical,
            String model,
            String modelVersion,
            boolean enabled) {
        this(semantic, lexical, model, modelVersion, enabled, null, false, null);
    }

    public RrfDocumentSearch(
            SemanticDocumentSearch semantic,
            LexicalSectionSearch lexical,
            String model,
            String modelVersion,
            boolean enabled,
            DocumentReranker reranker,
            boolean rerankingEnabled,
            RerankingEvents events) {
        this.semantic = semantic;
        this.lexical = lexical;
        this.model = model;
        this.modelVersion = modelVersion;
        this.enabled = enabled;
        this.reranker = reranker;
        this.rerankingEnabled = rerankingEnabled;
        this.events = events;
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
        List<Match> fused = ReciprocalRankFusion.fuse(vector, terms, CANDIDATE_LIMIT);
        if (!rerankingEnabled || reranker == null || fused.size() < 2) {
            return fused.stream().limit(limit).toList();
        }
        long started = System.nanoTime();
        try {
            List<Integer> indexes = reranker.rerank(question, fused);
            List<Match> reordered = applyOrder(fused, indexes);
            if (events != null) {
                events.completed(
                        Duration.ofNanos(System.nanoTime() - started), fused.size(), false);
            }
            return reordered.stream().limit(limit).toList();
        } catch (RuntimeException failure) {
            if (events != null) {
                events.completed(Duration.ofNanos(System.nanoTime() - started), fused.size(), true);
            }
            return fused.stream().limit(limit).toList();
        }
    }

    private static List<Match> applyOrder(List<Match> candidates, List<Integer> indexes) {
        if (indexes == null || indexes.size() != candidates.size()) {
            throw new IllegalStateException("Invalid reranking order");
        }
        Set<Integer> seen = new HashSet<>();
        var ordered = new ArrayList<Match>(indexes.size());
        for (Integer index : indexes) {
            if (index == null || index < 0 || index >= candidates.size() || !seen.add(index)) {
                throw new IllegalStateException("Invalid reranking order");
            }
            ordered.add(candidates.get(index));
        }
        return List.copyOf(ordered);
    }
}
