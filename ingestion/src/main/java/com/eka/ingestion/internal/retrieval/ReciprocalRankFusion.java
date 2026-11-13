package com.eka.ingestion.internal.retrieval;

import com.eka.ingestion.api.DocumentRetrieval.Match;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Deterministically fuses semantic and lexical chunk rankings using reciprocal rank fusion. */
final class ReciprocalRankFusion {
    private static final int RANK_CONSTANT = 60;

    private ReciprocalRankFusion() {}

    static List<Match> fuse(List<Match> semantic, List<Match> lexical, int limit) {
        if (semantic == null || lexical == null || limit < 1 || limit > 10) {
            throw new IllegalArgumentException("Invalid reciprocal rank fusion input");
        }
        var fused = new HashMap<Key, RankedMatch>();
        add(fused, semantic, false);
        add(fused, lexical, true);
        return fused.values().stream()
                .sorted(
                        Comparator.comparingDouble(RankedMatch::score)
                                .reversed()
                                .thenComparing(ranked -> ranked.match().documentId().toString())
                                .thenComparingLong(ranked -> ranked.match().sourceGeneration())
                                .thenComparingInt(ranked -> ranked.match().chunkOrdinal()))
                .limit(limit)
                .map(RankedMatch::match)
                .toList();
    }

    private static void add(Map<Key, RankedMatch> fused, List<Match> matches, boolean lexical) {
        Set<Key> seen = new HashSet<>();
        for (int index = 0; index < matches.size(); index++) {
            Match candidate = matches.get(index);
            if (candidate == null) {
                throw new IllegalArgumentException("Null retrieval candidate");
            }
            Key key = Key.of(candidate);
            if (!seen.add(key)) {
                continue;
            }
            double contribution = 1.0 / (RANK_CONSTANT + index + 1);
            RankedMatch existing = fused.get(key);
            if (existing == null) {
                Match marked = lexical ? withLexicalMatch(candidate) : candidate;
                fused.put(key, new RankedMatch(marked, contribution));
            } else {
                Match preferred = lexical ? withLexicalMatch(existing.match()) : existing.match();
                fused.put(key, new RankedMatch(preferred, existing.score() + contribution));
            }
        }
    }

    private static Match withLexicalMatch(Match match) {
        if (match.lexicalMatch()) {
            return match;
        }
        return new Match(
                match.documentId(),
                match.filename(),
                match.sourceGeneration(),
                match.chunkOrdinal(),
                match.sectionOrdinal(),
                match.page(),
                match.startOffset(),
                match.endOffset(),
                match.text(),
                match.distance(),
                true);
    }

    private record Key(UUID documentId, long generation, int chunkOrdinal) {
        static Key of(Match match) {
            return new Key(match.documentId(), match.sourceGeneration(), match.chunkOrdinal());
        }
    }

    private record RankedMatch(Match match, double score) {}
}
