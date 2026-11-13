package com.eka.ingestion.api;

import java.util.List;

/** Combines tenant-scoped semantic and lexical retrieval into citation-ready evidence. */
public interface HybridDocumentSearch {
    List<DocumentRetrieval.Match> search(String question, int limit);
}
