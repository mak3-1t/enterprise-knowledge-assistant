package com.eka.ingestion.internal.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.eka.ingestion.api.DocumentRetrieval.Match;
import com.eka.ingestion.api.SemanticDocumentSearch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RrfDocumentSearchTest {
    @Test
    void fusesLexicalOnlyCandidatesWithSemanticResults() {
        var lexical = mock(LexicalSectionSearch.class);
        var semanticMatch = match(1, false);
        var lexicalMatch = match(2, true);
        SemanticDocumentSearch semantic = (question, limit) -> List.of(semanticMatch);
        when(lexical.searchChunks("policy", "fixture", "v1", 10)).thenReturn(List.of(lexicalMatch));
        var search = new RrfDocumentSearch(semantic, lexical, "fixture", "v1", true);

        var matches = search.search("policy", 5);

        assertThat(matches)
                .extracting(Match::documentId)
                .containsExactly(semanticMatch.documentId(), lexicalMatch.documentId());
        assertThat(matches).extracting(Match::lexicalMatch).containsExactly(false, true);
    }

    @Test
    void disablingHybridRetrievalKeepsTheVectorBaseline() {
        var lexical = mock(LexicalSectionSearch.class);
        var semanticMatch = match(1, false);
        SemanticDocumentSearch semantic = (question, limit) -> List.of(semanticMatch);
        var search = new RrfDocumentSearch(semantic, lexical, "fixture", "v1", false);

        assertThat(search.search("policy", 1)).containsExactly(semanticMatch);
        verify(lexical, never()).searchChunks("policy", "fixture", "v1", 10);
    }

    @Test
    void reranksOnlyTheBoundedFusedCandidates() {
        var lexical = mock(LexicalSectionSearch.class);
        var first = match(1, false);
        var second = match(2, false);
        SemanticDocumentSearch semantic = (question, limit) -> List.of(first, second);
        when(lexical.searchChunks("policy", "fixture", "v1", 10)).thenReturn(List.of());
        var search =
                new RrfDocumentSearch(
                        semantic,
                        lexical,
                        "fixture",
                        "v1",
                        true,
                        (query, candidates) -> {
                            assertThat(query).isEqualTo("policy");
                            assertThat(candidates).containsExactly(first, second);
                            return List.of(1, 0);
                        },
                        true,
                        null);

        assertThat(search.search("policy", 1)).containsExactly(second);
    }

    @Test
    void rerankerFailureFallsBackToTheStableHybridRanking() {
        var lexical = mock(LexicalSectionSearch.class);
        var first = match(1, false);
        var second = match(2, false);
        when(lexical.searchChunks("policy", "fixture", "v1", 10)).thenReturn(List.of());
        var search =
                new RrfDocumentSearch(
                        (question, limit) -> List.of(first, second),
                        lexical,
                        "fixture",
                        "v1",
                        true,
                        (query, candidates) -> {
                            throw new IllegalStateException("provider unavailable");
                        },
                        true,
                        null);

        assertThat(search.search("policy", 2)).containsExactly(first, second);
    }

    @Test
    void invalidRerankerOrderFallsBackWithoutDroppingEvidence() {
        var lexical = mock(LexicalSectionSearch.class);
        var first = match(1, false);
        var second = match(2, false);
        when(lexical.searchChunks("policy", "fixture", "v1", 10)).thenReturn(List.of());
        var search =
                new RrfDocumentSearch(
                        (question, limit) -> List.of(first, second),
                        lexical,
                        "fixture",
                        "v1",
                        true,
                        (query, candidates) -> List.of(1, 1),
                        true,
                        null);

        assertThat(search.search("policy", 2)).containsExactly(first, second);
    }

    @Test
    void boundsQueryAndResultLimit() {
        var search =
                new RrfDocumentSearch(
                        (question, limit) -> List.of(),
                        mock(LexicalSectionSearch.class),
                        "fixture",
                        "v1",
                        true);

        assertThatThrownBy(() -> search.search(" ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search("question", 11))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Match match(int id, boolean lexical) {
        return new Match(
                new UUID(0, id),
                "policy-" + id + ".txt",
                1,
                0,
                0,
                null,
                0,
                6,
                "policy",
                lexical ? 1.0 : 0.2,
                lexical);
    }
}
