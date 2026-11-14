package com.eka.ingestion.internal.retrieval;

import com.eka.ingestion.api.DocumentReranker;
import com.eka.ingestion.api.DocumentRetrieval.Match;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Calls a locally or privately hosted Text Embeddings Inference rerank endpoint. */
public final class TeiDocumentReranker implements DocumentReranker {
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_QUERY_CHARS = 2000;
    private static final int MAX_TEXT_CHARS = 6000;
    private static final ParameterizedTypeReference<List<Rank>> RANKS =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;

    public TeiDocumentReranker(RestClient client) {
        this.client = client;
    }

    @Override
    public List<Integer> rerank(String query, List<Match> candidates) {
        if (query == null
                || query.isBlank()
                || query.length() > MAX_QUERY_CHARS
                || candidates == null
                || candidates.size() > MAX_CANDIDATES
                || candidates.stream()
                        .anyMatch(candidate -> candidate == null || candidate.text() == null)) {
            throw new IllegalArgumentException("Invalid reranking input");
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        var request =
                new Request(
                        query.strip(),
                        candidates.stream()
                                .map(Match::text)
                                .map(
                                        text ->
                                                text.substring(
                                                        0, Math.min(text.length(), MAX_TEXT_CHARS)))
                                .toList(),
                        true);
        List<Rank> ranks = client.post().uri("/rerank").body(request).retrieve().body(RANKS);
        return validateAndSort(ranks, candidates.size());
    }

    static List<Integer> validateAndSort(List<Rank> ranks, int candidateCount) {
        if (ranks == null || ranks.size() != candidateCount) {
            throw new IllegalStateException("Invalid reranking response count");
        }
        Set<Integer> seen = new HashSet<>();
        for (Rank rank : ranks) {
            if (rank == null
                    || rank.index() < 0
                    || rank.index() >= candidateCount
                    || !Double.isFinite(rank.score())
                    || !seen.add(rank.index())) {
                throw new IllegalStateException("Invalid reranking response");
            }
        }
        return ranks.stream()
                .sorted(
                        java.util.Comparator.comparingDouble(Rank::score)
                                .reversed()
                                .thenComparingInt(Rank::index))
                .map(Rank::index)
                .toList();
    }

    public static URI validateEndpoint(String endpoint) {
        URI uri = URI.create(endpoint);
        if (uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Invalid reranking endpoint");
        }
        return uri;
    }

    private record Request(String query, List<String> texts, boolean truncate) {}

    record Rank(int index, double score) {}
}
