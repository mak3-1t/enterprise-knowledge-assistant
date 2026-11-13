package com.eka.ingestion.internal.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eka.ingestion.api.DocumentRetrieval.Match;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {
    @Test
    void combinesRanksAndPromotesCandidatesPresentInBothLists() {
        Match first = match(1, 0, 0, false);
        Match shared = match(2, 0, 0, false);
        Match lexicalOnly = match(3, 0, 0, true);

        var results =
                ReciprocalRankFusion.fuse(List.of(first, shared), List.of(shared, lexicalOnly), 10);

        assertThat(results)
                .extracting(Match::documentId)
                .containsExactly(shared.documentId(), first.documentId(), lexicalOnly.documentId());
        assertThat(results).extracting(Match::lexicalMatch).containsExactly(true, false, true);
        assertThat(results.getFirst().distance()).isEqualTo(shared.distance());
        assertThat(results.getLast().distance()).isEqualTo(lexicalOnly.distance());
    }

    @Test
    void deduplicatesEachRankingBoundsResultsAndKeepsStableTieOrder() {
        Match first = match(1, 0, 0, false);
        Match duplicate = match(1, 0, 0, false);
        Match second = match(2, 0, 0, false);

        var results = ReciprocalRankFusion.fuse(List.of(first, duplicate, second), List.of(), 1);

        assertThat(results).containsExactly(first);
    }

    @Test
    void rejectsInvalidLimitAndNullCandidates() {
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(List.of(), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ReciprocalRankFusion.fuse(
                                        java.util.Arrays.asList((Match) null), List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Match match(int id, int generation, int chunk, boolean lexical) {
        return new Match(
                new UUID(0, id),
                "document-" + id + ".txt",
                generation,
                chunk,
                0,
                null,
                0,
                4,
                "text",
                lexical ? 1.0 : 0.1,
                lexical);
    }
}
