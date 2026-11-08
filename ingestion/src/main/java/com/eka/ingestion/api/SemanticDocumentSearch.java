package com.eka.ingestion.api;

import java.util.List;

/** Embeds a question and retrieves evidence with the same model as ingestion. */
public interface SemanticDocumentSearch {
    List<DocumentRetrieval.Match> search(String question, int limit);
}
