package com.eka.ingestion.api;

import com.eka.ingestion.api.DocumentRetrieval.Match;
import java.util.List;

/** Returns candidate indexes ordered by relevance without changing retrieved evidence. */
public interface DocumentReranker {
    List<Integer> rerank(String query, List<Match> candidates);
}
